package co.wethinkcode.healthsafe;

import io.javalin.Javalin;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AlertLevelServiceApp {

    private static final int MIN_LEVEL = 0;
    private static final int MAX_LEVEL = 8;

    // Starts at 0 (no active emergency) until explicitly set.
    private static final AtomicInteger currentLevel = new AtomicInteger(0);

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(7032);

        app.get("/health", ctx -> ctx.result("OK"));

        // Read the current Emergency Status.
        app.get("/alert-level", ctx -> ctx.json(Map.of("level", currentLevel.get())));

    
        app.put("/alert-level", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            Object rawLevel = body.get("level");

            if (!(rawLevel instanceof Integer newLevel)) {
                ctx.status(400).json(Map.of(
                        "error", "Missing or invalid 'level' field - must be an integer"
                ));
                return;
            }

            if (newLevel < MIN_LEVEL || newLevel > MAX_LEVEL) {
                ctx.status(400).json(Map.of(
                        "error", "level must be between " + MIN_LEVEL + " and " + MAX_LEVEL,
                        "received", newLevel
                ));
                return;
            }

            currentLevel.set(newLevel);
            ctx.json(Map.of("level", currentLevel.get()));
        });
    }
}