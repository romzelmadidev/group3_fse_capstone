package com.bank.orchestrator.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reversals")
public class ReversalOrchestratorController {

    private final WebClient cbsWebClient;

    public ReversalOrchestratorController(
            WebClient.Builder webClientBuilder,
            @Value("${services.cbs.url:http://localhost:8085}") String cbsServiceUrl) {
        this.cbsWebClient = webClientBuilder.baseUrl(cbsServiceUrl).build();
    }

    @PostMapping("/request")
    public ResponseEntity<Map<?, ?>> requestReversal(@RequestBody Map<String, Object> request) {
        Map<?, ?> response = cbsWebClient.post()
                .uri("/api/v1/cbs/reversals/request")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<?, ?>> approveReversal(@RequestBody Map<String, Object> request) {
        Map<?, ?> response = cbsWebClient.post()
                .uri("/api/v1/cbs/reversals/approve")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<?, ?>> rejectReversal(@RequestBody Map<String, Object> request) {
        Map<?, ?> response = cbsWebClient.post()
                .uri("/api/v1/cbs/reversals/reject")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/direct", "/compensate"})
    public ResponseEntity<Map<?, ?>> directReversal(@RequestBody Map<String, Object> request) {
        Map<?, ?> response = cbsWebClient.post()
                .uri("/t24/reversal")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(3000))
                .block();
        return ResponseEntity.ok(response);
    }
}
