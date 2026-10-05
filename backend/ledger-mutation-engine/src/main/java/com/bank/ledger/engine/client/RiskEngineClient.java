package com.bank.ledger.engine.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class RiskEngineClient {

    private final String riskEngineUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RiskEngineClient(
            @Value("${app.risk-engine.url:http://risk-engine:8084}") String riskEngineUrl,
            ObjectMapper objectMapper) {
        this.riskEngineUrl = riskEngineUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(200))
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RiskAssessmentResult {
        private double riskScore;
        private String decision; // ALLOW or DENY
        private String reason;
        private double velocityKmh;
        private double distanceKm;
    }

    /**
     * Evaluates transaction risk against Python Risk Screening Engine under strict <= 200ms SLA.
     */
    public RiskAssessmentResult evaluateRisk(
            String userId,
            String accountId,
            BigDecimal amount,
            Double currentLat,
            Double currentLon,
            String currentCity,
            Double previousLat,
            Double previousLon,
            String previousCity,
            Long timeDiffSeconds) {

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("user_id", userId != null ? userId : "U1001");
            payload.put("account_id", accountId);
            payload.put("amount", amount != null ? amount.doubleValue() : 0.0);
            payload.put("current_lat", currentLat);
            payload.put("current_lon", currentLon);
            payload.put("current_city", currentCity != null ? currentCity : "Unknown");
            payload.put("previous_lat", previousLat);
            payload.put("previous_lon", previousLon);
            payload.put("previous_city", previousCity != null ? previousCity : "Unknown");
            payload.put("time_diff_seconds", timeDiffSeconds != null ? timeDiffSeconds.doubleValue() : null);

            String requestBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(riskEngineUrl + "/api/v1/risk/evaluate"))
                    .timeout(Duration.ofMillis(250))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode json = objectMapper.readTree(response.body());
                return RiskAssessmentResult.builder()
                        .riskScore(json.path("risk_score").asDouble(0.10))
                        .decision(json.path("decision").asText("ALLOW"))
                        .reason(json.path("reason").asText("OK"))
                        .velocityKmh(json.path("velocity_kmh").asDouble(0.0))
                        .distanceKm(json.path("distance_km").asDouble(0.0))
                        .build();
            } else {
                log.warn("[RISK CLIENT] Python risk engine returned status: {}", response.statusCode());
            }
        } catch (Exception ex) {
            log.warn("[RISK CLIENT SLA] Unable to reach Python risk engine within SLA ({}): {}", riskEngineUrl, ex.getMessage());
        }

        // Safe baseline fallback when risk engine is cold/offline
        return RiskAssessmentResult.builder()
                .riskScore(0.10)
                .decision("ALLOW")
                .reason("RISK_ENGINE_FALLBACK_DEFAULT_ALLOW")
                .velocityKmh(0.0)
                .distanceKm(0.0)
                .build();
    }
}
