package co.wethinkcode.healthsafe;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import io.javalin.Javalin;
 
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

public class IngestionServiceApp {

        record WardRecord(
            String wardId,
            String wing,
            String department,
            Integer bedsAvailable,
            String notes
    ) {}
 
    private static final Set<String> PLACEHOLDER_VALUES = Set.of(
            "n/a", "na", "tbd", "unknown", "-", "nan", ""
    );

     public static void main(String[] args) throws Exception {
        List<WardRecord> wards = loadAndCleanWards();
 
        Javalin app = Javalin.create().start(7030);
 
        app.get("/health", ctx -> ctx.result("OK"));
 
        app.get("/wards", ctx -> ctx.json(wards));
    }
 
    static List<WardRecord> loadAndCleanWards() throws IOException {
        List<WardRecord> cleaned = new ArrayList<>();
 
        InputStream csvStream = IngestionServiceApp.class
                .getClassLoader()
                .getResourceAsStream("wards-outdated.csv");
 
        if (csvStream == null) {
            throw new IOException("wards-outdated.csv not found on classpath");
        }
 
        try (CSVReader reader = new CSVReaderBuilder(new InputStreamReader(csvStream))
                .withSkipLines(1) // skip header row
                .build()) {
 
            String[] row;
            while ((row = reader.readNext()) != null) {
                if (row.length < 4) continue; // skip malformed rows
                cleaned.add(cleanRow(row));
            }
        } catch (Exception e) {
            throw new IOException("Failed to parse wards-outdated.csv", e);
        }
 
        return mergeDuplicates(cleaned);
    }
 
    private static WardRecord cleanRow(String[] row) {
        String wardId = normalizeSpacing(row[0]).toUpperCase();
        String wing = titleCase(normalizeSpacing(row[1]));
        String department = normalizeDepartment(titleCase(normalizeSpacing(row[2])));
        String rawBeds = normalizeSpacing(row[3]);
 
        BedsResult beds = parseBeds(rawBeds);
 
        return new WardRecord(wardId, wing, department, beds.value, beds.note);
    }
 
    /** Collapses multiple internal spaces and trims leading/trailing whitespace. */
    private static String normalizeSpacing(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }
 
    private static String titleCase(String value) {
        if (value == null || value.isBlank()) return value;
        String[] words = value.toLowerCase().split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0)))
              .append(w.substring(1))
              .append(" ");
        }
        return sb.toString().trim();
    }
 
    /** Normalizes known spelling variants to one canonical form. */
    private static String normalizeDepartment(String department) {
        if (department.equalsIgnoreCase("Pediatrics")) {
            return "Paediatrics";
        }
        return department;
    }
 
    private record BedsResult(Integer value, String note) {}
 
    private static BedsResult parseBeds(String raw) {
        String normalized = raw.trim().toLowerCase();
 
        if (PLACEHOLDER_VALUES.contains(normalized)) {
            return new BedsResult(null,
                    "bedsAvailable was missing/placeholder ('" + raw + "') - flagged for follow-up");
        }
 
        int parsed;
        try {
            parsed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return new BedsResult(null,
                    "bedsAvailable was non-numeric ('" + raw + "') - flagged for follow-up");
        }
 
        if (parsed < 0) {
            return new BedsResult(null,
                    "bedsAvailable was negative (" + parsed + ") - flagged for follow-up");
        }
 
        if (parsed > 50) {
            
            return new BedsResult(null,
                    "bedsAvailable value (" + parsed + ") looks unrealistic - flagged for follow-up");
        }
 
        return new BedsResult(parsed, null);
    }
 

    private static List<WardRecord> mergeDuplicates(List<WardRecord> records) {
        Map<String, WardRecord> byId = new LinkedHashMap<>();
 
        for (WardRecord incoming : records) {
            WardRecord existing = byId.get(incoming.wardId());
 
            if (existing == null) {
                byId.put(incoming.wardId(), incoming);
                continue;
            }
 
            byId.put(incoming.wardId(), mergeTwo(existing, incoming));
        }
 
        return new ArrayList<>(byId.values());
    }
 
    private static WardRecord mergeTwo(WardRecord a, WardRecord b) {
        // Prefer whichever record has a valid (non-null) beds value.
        Integer beds = a.bedsAvailable() != null ? a.bedsAvailable() : b.bedsAvailable();
 
        String note;
        if (a.bedsAvailable() != null && b.bedsAvailable() != null
                && !a.bedsAvailable().equals(b.bedsAvailable())) {
            note = "merged duplicate record for " + a.wardId()
                    + "; conflicting bedsAvailable values (" + a.bedsAvailable()
                    + " vs " + b.bedsAvailable() + ") - kept " + beds;
        } else {
            note = "merged duplicate record for " + a.wardId();
        }
 
        return new WardRecord(a.wardId(), a.wing(), a.department(), beds, note);
    }
}
