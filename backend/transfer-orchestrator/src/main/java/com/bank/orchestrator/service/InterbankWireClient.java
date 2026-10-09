package com.bank.orchestrator.service;

import com.bank.orchestrator.dto.InterbankWireRequest;
import com.bank.orchestrator.dto.InterbankWireResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;

/**
 * Dispatches outbound inter-bank wire transfers to the Partner Bank gateway.
 * Configured with a strict timeout so network hangs trigger automated Saga compensation.
 */
@Service
public class InterbankWireClient {

    private static final Logger log = LoggerFactory.getLogger(InterbankWireClient.class);

    private final WebClient webClient;
    private final String partnerUrl;
    private final String apiKey;

    public InterbankWireClient(
            WebClient.Builder webClientBuilder,
            @Value("${services.partner.url:http://localhost:8082/api/v1/mock-partner/transfers}") String partnerUrl,
            @Value("${services.partner.api-key:AURABANK-SECURE-KEY-2026}") String apiKey) {
        this.partnerUrl = partnerUrl;
        this.apiKey = apiKey;
        this.webClient = webClientBuilder.build();
    }

    public InterbankWireResponse dispatchWire(InterbankWireRequest request, String simulationMode) {
        log.info("Dispatching inter-bank wire: refId={}, originating={}, beneficiary={}, amount={}, destUrl={}",
                request.referenceId(), request.originatingBank(), request.beneficiaryAccountId(), request.amount(), partnerUrl);

        String fullUrl = partnerUrl;
        if (simulationMode != null && !simulationMode.isBlank()) {
            fullUrl += (partnerUrl.contains("?") ? "&" : "?") + "simulationMode=" + simulationMode;
        }

        return webClient.post()
                .uri(URI.create(fullUrl))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-Interbank-API-Key", apiKey)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(InterbankWireResponse.class)
                .timeout(Duration.ofMillis(4000)) // 4-second timeout to trigger Saga rollback on lag
                .block();
    }
}
