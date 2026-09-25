package com.bank.ledger.engine.controller;

import com.bank.ledger.contracts.dto.MutationRequest;
import com.bank.ledger.contracts.dto.MutationResponse;
import com.bank.ledger.contracts.exception.InsufficientFundsException;
import com.bank.ledger.engine.service.BalanceMutationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class BalanceMutationController {

    private final BalanceMutationService mutationService;

    /**
     * Core Retail Balance Mutation API (Funds Transfer)
     * Protected by Pessimistic Locking, Dual-Write Postgres Audit, and Kafka Event Bus.
     */
    @PostMapping("/transfer")
    public ResponseEntity<MutationResponse> executeTransfer(@Valid @RequestBody MutationRequest request) {
        log.info("[HTTP REQUEST] POST /api/v1/ledger/transfer from account: {} to {}",
                request.getAccountId(), request.getTargetAccountId());

        MutationResponse response = mutationService.executeTransfer(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Banking Standard: Financial Sanity Exception Handler
     * Returns HTTP 422 Unprocessable Entity when funds are insufficient.
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "status", "REJECTED",
                "error_code", "INSUFFICIENT_FUNDS",
                "message", ex.getMessage(),
                "timestamp", Instant.now()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", "REJECTED",
                "error_code", "INVALID_ARGUMENT",
                "message", ex.getMessage(),
                "timestamp", Instant.now()
        ));
    }
}