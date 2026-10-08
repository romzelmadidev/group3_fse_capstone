package com.bank.orchestrator.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Deprecated Amount Hold Controller.
 * Core banking hold endpoints have been removed in favor of 1-step atomic transfers with pessimistic row locking.
 */
@RestController
public class AmountHoldOrchestratorController {

    private static final Logger log = LoggerFactory.getLogger(AmountHoldOrchestratorController.class);

    private static final Map<String, String> DEPRECATED_RESPONSE = Map.of(
            "status", "DEPRECATED",
            "message", "Core banking hold endpoints have been decommissioned. Atomic transfers with pessimistic row locking are enforced."
    );

    @PostMapping(path = {"/api/v1/orchestrator/accounts/{accountId}/holds", "/api/v1/accounts/{accountId}/holds"})
    public ResponseEntity<Map<String, String>> placeAccountHold(
            @PathVariable String accountId,
            @RequestBody(required = false) Map<String, Object> request) {
        log.warn("Call to decommissioned hold endpoint for account {}", accountId);
        return ResponseEntity.status(HttpStatus.GONE).body(DEPRECATED_RESPONSE);
    }

    @PostMapping("/api/v1/holds")
    public ResponseEntity<Map<String, String>> placeHold(@RequestBody(required = false) Map<String, Object> request) {
        log.warn("Call to decommissioned direct hold endpoint");
        return ResponseEntity.status(HttpStatus.GONE).body(DEPRECATED_RESPONSE);
    }

    @DeleteMapping(path = {"/api/v1/orchestrator/accounts/{accountId}/holds/{holdId}", "/api/v1/accounts/{accountId}/holds/{holdId}"})
    public ResponseEntity<Map<String, String>> releaseAccountHold(
            @PathVariable String accountId,
            @PathVariable String holdId) {
        log.warn("Call to decommissioned hold release endpoint for hold {} on account {}", holdId, accountId);
        return ResponseEntity.status(HttpStatus.GONE).body(DEPRECATED_RESPONSE);
    }

    @DeleteMapping("/api/v1/holds/{holdId}")
    public ResponseEntity<Map<String, String>> deleteHold(@PathVariable String holdId) {
        log.warn("Call to decommissioned direct hold release for hold {}", holdId);
        return ResponseEntity.status(HttpStatus.GONE).body(DEPRECATED_RESPONSE);
    }

    @PostMapping("/api/v1/holds/{holdId}/release")
    public ResponseEntity<Map<String, String>> releaseHoldDirect(@PathVariable String holdId) {
        return deleteHold(holdId);
    }

    @PostMapping(path = {
            "/api/v1/orchestrator/accounts/{accountId}/holds/{holdId}/capture",
            "/api/v1/accounts/{accountId}/holds/{holdId}/capture",
            "/api/v1/holds/{holdId}/capture"
    })
    public ResponseEntity<Map<String, String>> captureAccountHold(
            @PathVariable(required = false) String accountId,
            @PathVariable String holdId,
            @RequestBody(required = false) Map<String, Object> request) {
        log.warn("Call to decommissioned hold capture endpoint for hold {}", holdId);
        return ResponseEntity.status(HttpStatus.GONE).body(DEPRECATED_RESPONSE);
    }

    @GetMapping(path = {"/api/v1/orchestrator/accounts/{accountId}/holds", "/api/v1/holds/account/{accountId}"})
    public ResponseEntity<List<?>> getHoldsForAccount(@PathVariable String accountId) {
        log.warn("Call to decommissioned get-holds endpoint for account {}", accountId);
        return ResponseEntity.ok(Collections.emptyList());
    }
}
