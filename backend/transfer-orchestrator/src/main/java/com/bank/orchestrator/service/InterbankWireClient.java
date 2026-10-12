package com.bank.orchestrator.service;

import com.bank.orchestrator.dto.InterbankWireRequest;
import com.bank.orchestrator.dto.InterbankWireResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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
    private final ObjectMapper objectMapper;
    private final BankRoutingService bankRoutingService;

    public InterbankWireClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${services.partner.url:}") String partnerUrl,
            @Value("${services.partner.api-key:AURABANK-SECURE-KEY-2026}") String apiKey) {
        this(webClientBuilder, objectMapper, null, partnerUrl, apiKey);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InterbankWireClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false) BankRoutingService bankRoutingService,
            @Value("${services.partner.url:}") String partnerUrl,
            @Value("${services.partner.api-key:AURABANK-SECURE-KEY-2026}") String apiKey) {
        String effectiveUrl = (partnerUrl != null && !partnerUrl.trim().isEmpty())
                ? partnerUrl.trim()
                : "http://localhost:8082/api/v1/mock-partner/transfers";
        this.partnerUrl = effectiveUrl;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.bankRoutingService = bankRoutingService;
        this.webClient = webClientBuilder.build();
    }

    public InterbankWireResponse dispatchWire(InterbankWireRequest request, String simulationMode) {
        String resolvedBaseUrl = (bankRoutingService != null)
                ? bankRoutingService.resolveEffectiveEndpointUrl(request.destinationBankCode())
                : partnerUrl;

        log.info("Dispatching inter-bank wire: messageId={}, sourceBank={}, destBank={}, beneficiary={}, amount={}, destUrl={}",
                request.messageId(), request.sourceBankCode(), request.destinationBankCode(),
                request.getBeneficiaryAccountNumber(), request.amount(), resolvedBaseUrl);

        String fullUrl = resolvedBaseUrl;
        if (simulationMode != null && !simulationMode.isBlank()) {
            fullUrl += (resolvedBaseUrl.contains("?") ? "&" : "?") + "simulationMode=" + simulationMode;
        }

        try {
            return webClient.post()
                    .uri(URI.create(fullUrl))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Interbank-API-Key", apiKey)
                    .header("X-API-Key", apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(InterbankWireResponse.class)
                    .timeout(Duration.ofMillis(4000)) // 4-second timeout to trigger Saga rollback on lag
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("Partner bank returned HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            try {
                InterbankWireResponse parsed = objectMapper.readValue(e.getResponseBodyAsString(), InterbankWireResponse.class);
                if (parsed != null && parsed.status() != null) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // Not a structured InterbankWireResponse, rethrow original exception
            }
            throw e;
        }
    }
}
