package co.wethinkcode.healthsafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class StaffingServiceApp {

    private static final String WARD_SERVICE_URL = "http://localhost:7031";
    private static final String ALERT_LEVEL_SERVICE_URL = "http://localhost:7032/alert-level";

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * A small mock doctor roster per department. There is no real staff
     * database anywhere in this project, so this stands in for one - a
     * documented, deliberate simplification rather than an oversight.
     */
    private static final Map<String, List<String>> DOCTOR_ROSTER = Map.of(
            "Cardiology", List.of("Dr. Naidoo", "Dr. Smith", "Dr. Okafor"),
            "Paediatrics", List.of("Dr. van der Merwe", "Dr. Patel"),
            "Oncology", List.of("Dr. Mokoena", "Dr. Chen", "Dr. Adams"),
            "Radiology", List.of("Dr. Botha", "Dr. Singh"),
            "ICU", List.of("Dr. Dlamini", "Dr. Fischer", "Dr. Reyes"),
            "Maternity", List.of("Dr. Khumalo", "Dr. Green")
    );

    private static final List<String> DEFAULT_ROSTER =
            List.of("Dr. On-Call (general)");

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7033);

        app.get("/health", ctx -> ctx.result("OK"));

        app.get("/schedule/{wardId}", ctx -> {
            String wardId = ctx.pathParam("wardId").toUpperCase();

            Map<String, Object> ward;
            try {
                ward = fetchWard(wardId);
            } catch (WardNotFoundException e) {
                ctx.status(404).json(Map.of(
                        "error", "Ward not found",
                        "wardId", wardId
                ));
                return;
            } catch (Exception e) {
                ctx.status(503).json(Map.of(
                        "error", "ward-service unavailable",
                        "detail", e.getMessage()
                ));
                return;
            }

            int alertLevel;
            try {
                alertLevel = fetchAlertLevel();
            } catch (Exception e) {
                ctx.status(503).json(Map.of(
                        "error", "alert-level-service unavailable",
                        "detail", e.getMessage()
                ));
                return;
            }

            String department = (String) ward.get("department");
            List<String> onCallDoctors = buildSchedule(department, alertLevel);

            ctx.json(Map.of(
                    "wardId", wardId,
                    "department", department,
                    "alertLevel", alertLevel,
                    "onCallDoctors", onCallDoctors
            ));
        });

        // MQ TODO: publishes to ActiveMQ topic MqConfig.TOPIC at MqConfig.BROKER_URL
        // whenever a schedule is computed/changed, so ward-service can react
        // asynchronously instead of staffing-service being polled directly.
    }

     
    private static class WardNotFoundException extends RuntimeException {}
 
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fetchWard(String wardId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WARD_SERVICE_URL + "/wards/" + wardId))
                .GET()
                .build();
 
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());
 
        if (response.statusCode() == 404) {
            throw new WardNotFoundException();
        }
 
        if (response.statusCode() != 200) {
            throw new RuntimeException("ward-service returned status " + response.statusCode());
        }
 
        return mapper.readValue(response.body(), Map.class);
    }




}