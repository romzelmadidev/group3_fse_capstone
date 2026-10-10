package com.bank.orchestrator.service;

import com.bank.orchestrator.dto.RiskScoreRequest;
import com.bank.orchestrator.dto.RiskScoreResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Service
public class RiskClientService {

    private static final Logger log = LoggerFactory.getLogger(RiskClientService.class);

    private final WebClient webClient;

    public RiskClientService(
            WebClient.Builder webClientBuilder,
            @Value("${services.risk.url:http://localhost:8084}") String riskServiceUrl) {
        this.webClient = webClientBuilder.baseUrl(riskServiceUrl).build();
    }

    public RiskScoreResponse evaluateRisk(RiskScoreRequest request) {
        try {
            return webClient.post()
                    .uri("/api/v1/risk/score")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(RiskScoreResponse.class)
                    .timeout(Duration.ofMillis(2000))
                    .block();
        } catch (Exception e) {
            log.warn("Risk service unavailable or timed out: {}. Applying tiered contingency scoring.", e.getMessage());
            java.math.BigDecimal amt = request.amount() != null ? request.amount() : java.math.BigDecimal.ZERO;

            // Tier 3: High Value (>= ₱250,000) -> Advisory Hold
            if (amt.compareTo(new java.math.BigDecimal("250000.00")) >= 0) {
                return new RiskScoreResponse(request.transactionId(), 75, "ADVISORY_WARNING",
                        "Contingency: High-value transaction requires scam acknowledgment during risk engine degradation");
            }
            // Tier 2: Mid Value (₱10,000 - ₱249,999.99) -> Step-Up Biometric Authentication
            if (amt.compareTo(new java.math.BigDecimal("10000.00")) >= 0) {
                return new RiskScoreResponse(request.transactionId(), 65, "REQUIRE_2FA",
                        "Contingency: Strong Customer Authentication step-up required during risk engine degradation");
            }
            // Tier 1: Routine Low Value (< ₱10,000) -> STIP Allowance
            return new RiskScoreResponse(request.transactionId(), 15, "ALLOW",
                    "Contingency: Routine retail transaction permitted under Stand-In Processing limits");
        }
    }
}
