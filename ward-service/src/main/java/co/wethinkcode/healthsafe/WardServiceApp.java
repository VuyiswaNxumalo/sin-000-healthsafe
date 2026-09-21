package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import javax.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WardServiceApp {

    private static final String INGESTION_URL = "http://localhost:7030/wards";
    private static final int MAX_FETCH_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 2000;

    /** Mirrors ingestion-service's WardRecord shape. */
    record WardRecord(
            String wardId,
            String wing,
            String department,
            Integer bedsAvailable,
            String notes
    ) {}

    private static final Map<String, Map<String, Object>> latestStaffingByWard =
            new ConcurrentHashMap<>();

    private static final ObjectMapper mqMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Map<String, WardRecord> wardsById = fetchWardsFromIngestion();

        Javalin app = Javalin.create().start(7031);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/wards", ctx -> ctx.json(List.copyOf(wardsById.values())));

        app.get("/wards/{id}", ctx -> {
            String id = ctx.pathParam("id").toUpperCase();
            WardRecord ward = wardsById.get(id);

            if (ward == null) {
                ctx.status(404).json(Map.of(
                        "error", "Ward not found",
                        "wardId", id
                ));
                return;
            }

            ctx.json(ward);
        });

        app.get("/wards/{id}/staffing", ctx -> {
            String id = ctx.pathParam("id").toUpperCase();
            Map<String, Object> update = latestStaffingByWard.get(id);

            if (update == null) {
                ctx.status(404).json(Map.of(
                        "error", "No staffing update received yet for this ward",
                        "wardId", id
                ));
                return;
            }

            ctx.json(update);
        });

        subscribeToStaffingEvents();

        // MQ TODO: publishes to ActiveMQ queue MqConfig.QUEUE on equipment failure detection
    }

    @SuppressWarnings("unchecked")
    private static void subscribeToStaffingEvents() {
        try {
            ConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
            Connection connection = factory.createConnection();
            connection.start();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(MqConfig.TOPIC);
            MessageConsumer consumer = session.createConsumer(topic);

            consumer.setMessageListener(message -> {
                try {
                    if (message instanceof TextMessage textMessage) {
                        String json = textMessage.getText();
                        Map<String, Object> event = mqMapper.readValue(json, Map.class);
                        String wardId = (String) event.get("wardId");
                        latestStaffingByWard.put(wardId, event);
                        System.out.println("Received staffing update for " + wardId);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to process staffing event: " + e.getMessage());
                }
            });

            System.out.println("Subscribed to " + MqConfig.TOPIC);
        } catch (Exception e) {
            System.err.println("Could not subscribe to " + MqConfig.TOPIC
                    + " (continuing without it): " + e.getMessage());
        }
    }

    /**
     * Fetches the cleaned ward list from ingestion service, retrying a few
     * times with a short delay in case ingestion-service hasn't finished
     * starting yet. Returns an empty map (with a logged warning) if it's
     * still unavailable after all attempts, rather than crashing on startup.
     */
    private static Map<String, WardRecord> fetchWardsFromIngestion() {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newHttpClient();

        for (int attempt = 1; attempt <= MAX_FETCH_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(INGESTION_URL))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(
                        request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    List<WardRecord> records = mapper.readValue(
                            response.body(),
                            mapper.getTypeFactory().constructCollectionType(List.class, WardRecord.class)
                    );

                    Map<String, WardRecord> byId = new LinkedHashMap<>();
                    for (WardRecord record : records) {
                        byId.put(record.wardId(), record);
                    }

                    System.out.println("Loaded " + byId.size() + " wards from ingestion-service.");
                    return byId;
                }

                System.err.println("ingestion-service returned status "
                        + response.statusCode() + " (attempt " + attempt + "/" + MAX_FETCH_ATTEMPTS + ")");

            } catch (Exception e) {
                System.err.println("Could not reach ingestion-service (attempt "
                        + attempt + "/" + MAX_FETCH_ATTEMPTS + "): " + e.getMessage());
            }

            if (attempt < MAX_FETCH_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        System.err.println("WARNING: starting with an empty ward list - "
                + "ingestion-service was unreachable after " + MAX_FETCH_ATTEMPTS + " attempts.");
        return new LinkedHashMap<>();
    }
}