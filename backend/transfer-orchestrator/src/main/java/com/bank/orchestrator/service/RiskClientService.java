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
            log.warn("Risk service unavailable or timed out: {}. Applying default scoring.", e.getMessage());
            // Fallback heuristics: transactions >= 500,000 get advisory warning
            if (request.amount() != null && request.amount().compareTo(new java.math.BigDecimal("500000")) >= 0) {
                return new RiskScoreResponse(request.transactionId(), 65, "ADVISORY_WARNING", "Large value transfer threshold");
            }
            return new RiskScoreResponse(request.transactionId(), 15, "ALLOW", "Default low risk");
        }
    }
}
