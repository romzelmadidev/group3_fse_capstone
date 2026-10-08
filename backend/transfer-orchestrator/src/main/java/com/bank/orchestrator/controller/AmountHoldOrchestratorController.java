package com.bank.orchestrator.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class AmountHoldOrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(AmountHoldOrchestratorController.class);

    private final WebClient cbsWebClient;

    public AmountHoldOrchestratorController(
            WebClient.Builder webClientBuilder,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl) {
        this.cbsWebClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
    }

    /**
     * Places a hold on account balance according to Section 3.1 of API Specification.
     */
    @PostMapping(path = {"/api/v1/orchestrator/accounts/{accountId}/holds", "/api/v1/accounts/{accountId}/holds"})
    public ResponseEntity<Map<?, ?>> placeAccountHold(
            @PathVariable String accountId,
            @RequestBody Map<String, Object> request) {
        log.info("Orchestrator received hold request for account {}: {}", accountId, request);

        Map<String, Object> cbsPayload = new HashMap<>(request);
        cbsPayload.put("account_id", accountId);

        Map<?, ?> response = cbsWebClient.post()
                .uri("/api/v1/cbs/holds")
                .bodyValue(cbsPayload)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Direct hold initiation endpoint.
     */
    @PostMapping("/api/v1/holds")
    public ResponseEntity<Map<?, ?>> placeHold(@RequestBody Map<String, Object> request) {
        log.info("Orchestrator received direct hold request: {}", request);

        Map<?, ?> response = cbsWebClient.post()
                .uri("/api/v1/cbs/holds")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Releases a hold according to Section 3.2 of API Specification.
     */
    @DeleteMapping(path = {"/api/v1/orchestrator/accounts/{accountId}/holds/{holdId}", "/api/v1/accounts/{accountId}/holds/{holdId}"})
    public ResponseEntity<Map<?, ?>> releaseAccountHold(
            @PathVariable String accountId,
            @PathVariable String holdId) {
        log.info("Orchestrator releasing hold {} for account {}", holdId, accountId);

        Map<?, ?> response = cbsWebClient.delete()
                .uri("/api/v1/cbs/holds/" + holdId)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        return ResponseEntity.ok(response);
    }

    /**
     * Direct hold release endpoint.
     */
    @DeleteMapping("/api/v1/holds/{holdId}")
    public ResponseEntity<Map<?, ?>> deleteHold(@PathVariable String holdId) {
        log.info("Orchestrator releasing hold {}", holdId);

        Map<?, ?> response = cbsWebClient.delete()
                .uri("/api/v1/cbs/holds/" + holdId)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/v1/holds/{holdId}/release")
    public ResponseEntity<Map<?, ?>> releaseHoldDirect(@PathVariable String holdId) {
        return deleteHold(holdId);
    }

    @PostMapping(path = {
            "/api/v1/orchestrator/accounts/{accountId}/holds/{holdId}/capture",
            "/api/v1/accounts/{accountId}/holds/{holdId}/capture",
            "/api/v1/holds/{holdId}/capture"
    })
    public ResponseEntity<Map<?, ?>> captureAccountHold(
            @PathVariable(required = false) String accountId,
            @PathVariable String holdId,
            @RequestBody(required = false) Map<String, Object> request) {
        log.info("Orchestrator capturing hold {} for account {}", holdId, accountId);

        Map<String, Object> cbsPayload = request != null ? new HashMap<>(request) : new HashMap<>();
        cbsPayload.put("hold_id", holdId);
        if (accountId != null && !cbsPayload.containsKey("source_account_id")) {
            cbsPayload.put("source_account_id", accountId);
        }

        Map<?, ?> response = cbsWebClient.post()
                .uri("/api/v1/cbs/holds/" + holdId + "/capture")
                .bodyValue(cbsPayload)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        return ResponseEntity.ok(response);
    }

    @GetMapping(path = {"/api/v1/orchestrator/accounts/{accountId}/holds", "/api/v1/holds/account/{accountId}"})
    public ResponseEntity<List<?>> getHoldsForAccount(@PathVariable String accountId) {
        List<?> holds = cbsWebClient.get()
                .uri("/api/v1/cbs/holds/account/" + accountId)
                .retrieve()
                .bodyToMono(List.class)
                .timeout(Duration.ofMillis(3000))
                .block();

        return ResponseEntity.ok(holds);
    }
}
