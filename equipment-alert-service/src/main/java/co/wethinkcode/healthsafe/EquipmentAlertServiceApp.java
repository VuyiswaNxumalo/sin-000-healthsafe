package co.wethinkcode.healthsafe;

import co.wethinkcode.healthsafe.mq.MqConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class EquipmentAlertServiceApp {

    // Every alert successfully processed, kept for GET /alerts as visible
    // proof of what was actually delivered and handled.
    private static final List<Map<String, Object>> receivedAlerts =
            new CopyOnWriteArrayList<>();

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7034);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/alerts", ctx -> ctx.json(Collections.unmodifiableList(receivedAlerts)));

        subscribeToEquipmentFailures();
    }

    /**
     * Consumes equipment-failure-queue with guaranteed-delivery semantics.
     * Unlike staffing-events-topic (a broadcast every subscriber gets a copy
     * of), a queue delivers each message to exactly one consumer - and using
     * CLIENT_ACKNOWLEDGE mode here means a message is only removed from the
     * queue once it has been fully, successfully processed. If processing
     * throws before acknowledge() is called, the broker keeps the message
     * for redelivery instead of losing it - this is what "guaranteed
     * delivery" actually means in practice, not just "it's a queue."
     */
    @SuppressWarnings("unchecked")
    private static void subscribeToEquipmentFailures() {
        try {
            ConnectionFactory factory = new ActiveMQConnectionFactory(MqConfig.BROKER_URL);
            Connection connection = factory.createConnection();
            connection.start();

            Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            Queue queue = session.createQueue(MqConfig.QUEUE);
            MessageConsumer consumer = session.createConsumer(queue);

            consumer.setMessageListener(message -> {
                try {
                    if (message instanceof TextMessage textMessage) {
                        String json = textMessage.getText();
                        Map<String, Object> alert = mapper.readValue(json, Map.class);

                        // "Process" the alert - in a real system this might
                        // page an on-call technician, log to an incident
                        // system, etc. Here, storing it and printing it is
                        // the processing step.
                        receivedAlerts.add(alert);
                        System.out.println("ALERT: equipment failure on ward "
                                + alert.get("wardId") + " - " + alert.get("equipment")
                                + ": " + alert.get("description"));

                        // Only acknowledge after successful processing above -
                        // this is the guaranteed-delivery mechanism itself.
                        message.acknowledge();
                    }
                } catch (Exception e) {
                    // Deliberately NOT acknowledging here - an unacknowledged
                    // message is redelivered rather than lost, which is the
                    // whole point of using a queue with CLIENT_ACKNOWLEDGE
                    // for something as important as an equipment failure.
                    System.err.println("Failed to process equipment failure alert "
                            + "(will be redelivered): " + e.getMessage());
                }
            });

            System.out.println("Subscribed to " + MqConfig.QUEUE
                    + " (CLIENT_ACKNOWLEDGE - guaranteed delivery)");
        } catch (Exception e) {
            System.err.println("Could not subscribe to " + MqConfig.QUEUE
                    + " (continuing without it): " + e.getMessage());
        }
    }
}