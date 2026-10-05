package com.bank.risk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RiskEngineServer {

    private static final int PORT = 8084;
    private static final double EARTH_RADIUS_KM = 6371.0;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Health Endpoints
        server.createContext("/health", new HealthHandler());
        server.createContext("/actuator/health", new HealthHandler());

        // Risk Evaluation Endpoint
        server.createContext("/api/v1/risk/evaluate", new RiskEvaluationHandler());

        server.start();
        System.out.println("================================================================================");
        System.out.println(" 🛡️  CORE RETAIL BANKING - REAL-TIME FRAUD RISK SCREENING ENGINE (PORT " + PORT + ")");
        System.out.println(" SLA: <= 200 ms | Heuristics: Haversine Geovelocity & Impossible Travel");
        System.out.println("================================================================================");
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            String response = "{\"status\":\"UP\",\"service\":\"risk-engine\",\"version\":\"2.0.0\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class RiskEvaluationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            long startTime = System.nanoTime();
            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            String accountId = extractString(body, "account_id");
            Double currentLat = extractDouble(body, "current_lat");
            Double currentLon = extractDouble(body, "current_lon");
            String currentCity = extractString(body, "current_city");
            Double previousLat = extractDouble(body, "previous_lat");
            Double previousLon = extractDouble(body, "previous_lon");
            String previousCity = extractString(body, "previous_city");
            Double timeDiffSeconds = extractDouble(body, "time_diff_seconds");

            if (currentCity == null || currentCity.isBlank()) currentCity = "Unknown";
            if (previousCity == null || previousCity.isBlank()) previousCity = "Unknown";

            double riskScore = 0.05;
            String decision = "ALLOW";
            String reason = "NORMAL_GEOVELOCITY";
            double velocityKmh = 0.0;
            double distanceKm = 0.0;

            if (currentLat == null || currentLon == null) {
                riskScore = 0.10;
                decision = "ALLOW";
                reason = "NO_GEOLOCATION_PROVIDED_BASELINE_ALLOW";
            } else if (previousLat == null || previousLon == null) {
                riskScore = 0.05;
                decision = "ALLOW";
                reason = "FIRST_TRANSACTION_ESTABLISHED_BASELINE";
            } else {
                distanceKm = haversineKm(previousLat, previousLon, currentLat, currentLon);
                double timeSec = (timeDiffSeconds != null && timeDiffSeconds > 0) ? timeDiffSeconds : 60.0;
                double timeHours = timeSec / 3600.0;
                velocityKmh = timeHours > 0 ? (distanceKm / timeHours) : 0.0;

                // Commercial aircraft speed limit: 800 km/h (min distance 50 km to avoid GPS jitter)
                if (velocityKmh > 800.0 && distanceKm > 50.0) {
                    riskScore = 0.98;
                    decision = "DENY";
                    reason = String.format(
                            "IMPOSSIBLE_TRAVEL_DETECTED: Velocity %,.1f km/h between %s and %s (%,.1f km in %.0fs) exceeds maximum physical aircraft velocity of 800 km/h.",
                            velocityKmh, previousCity, currentCity, distanceKm, timeSec);
                    System.err.println("🚨 [FRAUD DETECTED] " + reason);
                } else if (velocityKmh > 300.0 && distanceKm > 30.0) {
                    riskScore = 0.55;
                    decision = "ALLOW";
                    reason = String.format("HIGH_VELOCITY_WARNING: %,.1f km/h detected between %s and %s", velocityKmh, previousCity, currentCity);
                } else {
                    riskScore = 0.05;
                    decision = "ALLOW";
                    reason = String.format("NORMAL_GEOVELOCITY: %,.1f km/h within legitimate travel limits", velocityKmh);
                }
            }

            long elapsedNs = System.nanoTime() - startTime;
            double elapsedMs = elapsedNs / 1_000_000.0;

            String jsonResponse = String.format(java.util.Locale.US,
                    "{\"risk_score\":%.2f,\"decision\":\"%s\",\"reason\":\"%s\",\"velocity_kmh\":%.2f,\"distance_km\":%.2f,\"evaluation_time_ms\":%.2f}",
                    riskScore,
                    decision,
                    reason.replace("\"", "\\\""),
                    velocityKmh,
                    distanceKm,
                    elapsedMs
            );

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2.0) * Math.sin(dLon / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return EARTH_RADIUS_KM * c;
    }

    private static String extractString(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static Double extractDouble(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\"\\s*:\\s*([0-9.-]+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
