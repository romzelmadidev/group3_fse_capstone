package com.bank.orchestrator.controller;

import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.dto.TransactionStatusHistoryDto;
import com.bank.orchestrator.dto.*;
import com.bank.orchestrator.service.BiometricChallengeService;
import com.bank.orchestrator.service.CoolOffService;
import com.bank.orchestrator.service.TransferOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferOrchestratorController {

    private final TransferOrchestrationService orchestrationService;
    private final BiometricChallengeService biometricService;
    private final CoolOffService coolOffService;
    private final com.bank.orchestrator.service.CbsClientService cbsService;
    private final ObjectMapper objectMapper;

    public TransferOrchestratorController(
            TransferOrchestrationService orchestrationService,
            BiometricChallengeService biometricService,
            CoolOffService coolOffService,
            com.bank.orchestrator.service.CbsClientService cbsService,
            ObjectMapper objectMapper) {
        this.orchestrationService = orchestrationService;
        this.biometricService = biometricService;
        this.coolOffService = coolOffService;
        this.cbsService = cbsService;
        this.objectMapper = objectMapper;
    }
    
    @GetMapping({"/accounts/{accountId}/transactions", "/transactions"})
    public ResponseEntity<List<AccountTransactionDto>> getAccountTransactions(
            @PathVariable(value = "accountId", required = false) String pathAccountId,
            @RequestParam(value = "accountId", required = false) String queryAccountId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        String accountId = (pathAccountId != null && !pathAccountId.isBlank()) ? pathAccountId : queryAccountId;
        if (accountId == null || accountId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<AccountTransactionDto> transactions = cbsService.getAccountTransactions(accountId, safePage, safeSize);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping({"/transactions/{transactionId}/status-history", "/{transactionId}/status-history"})
    public ResponseEntity<List<TransactionStatusHistoryDto>> getTransactionStatusHistory(
            @PathVariable String transactionId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        List<TransactionStatusHistoryDto> history = cbsService.getTransactionStatusHistory(transactionId, safePage, safeSize);
        return ResponseEntity.ok(history);
    }

    @PostMapping
    public ResponseEntity<TransferInitiationResponse> initiateTransfer(
            @Valid @RequestBody TransferInitiationRequest request) {
        TransferInitiationResponse response = orchestrationService.initiateTransfer(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-biometric")
    public ResponseEntity<TransferInitiationResponse> verifyBiometric(
            @Valid @RequestBody BiometricVerificationRequest request) {

        String destAccount = request.destinationAccountId();
        java.math.BigDecimal amount = request.amount();

        // Retrieve stored transfer from cooloff if available to bind context
        String payloadJson = coolOffService.getCoolOffPayload(request.transactionId());
        TransferInitiationRequest origReq = null;
        if (payloadJson != null) {
            try {
                origReq = objectMapper.readValue(payloadJson, TransferInitiationRequest.class);
                if (destAccount == null) {
                    destAccount = origReq.destinationAccountId();
                }
                if (amount == null) {
                    amount = origReq.amount();
                }
            } catch (Exception ignored) {
            }
        }

        boolean verified = biometricService.verifyChallenge(
                request.transactionId(),
                destAccount,
                amount,
                request.challengeToken(),
                request.assertionSignature(),
                request.deviceId()
        );

        if (!verified) {
            return ResponseEntity.badRequest().body(new TransferInitiationResponse(
                    request.transactionId(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    "Biometric assertion signature verification failed",
                    false,
                    0L,
                    true,
                    request.challengeToken(),
                    java.time.Instant.now()
            ));
        }
        // If original request was cached, execute the transfer now that biometrics passed
        if (origReq != null) {
            TransferInitiationResponse resp = orchestrationService.initiateTransfer(origReq);
            return ResponseEntity.ok(resp);
        }

        return ResponseEntity.ok(new TransferInitiationResponse(
                request.transactionId(),
                com.bank.ledger.contracts.enums.TransactionStatus.Authorized,
                null,
                null,
                null,
                null,
                "Biometric verified successfully. Ready to proceed.",
                false,
                0L,
                false,
                null,
                java.time.Instant.now()
        ));
    }

    @GetMapping("/{transactionId}/cool-off")
    public ResponseEntity<Map<String, Object>> checkCoolOffStatus(@PathVariable String transactionId) {
        boolean active = coolOffService.isInCoolOff(transactionId);
        Long remainingSeconds = coolOffService.getRemainingCoolOffSeconds(transactionId);
        return ResponseEntity.ok(Map.of(
                "transactionId", transactionId,
                "isCoolingOff", active,
                "remainingSeconds", remainingSeconds
        ));
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancelTransferDuringCoolOff(
            @Valid @RequestBody CoolOffCancelRequest request) {
        boolean cancelled = coolOffService.cancelCoolOff(request.transactionId());
        return ResponseEntity.ok(Map.of(
                "transactionId", request.transactionId(),
                "cancelled", cancelled,
                "message", cancelled ? "Transfer cancelled successfully during cooling-off window." : "Cooling-off window expired or not found"
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "TRANSACTION_DECLINED",
                    "code", "TX_DECLINED_POLICY",
                    "status", "Cancelled",
                    "message", "Transaction could not be processed at this time. Please contact customer support.",
                    "timestampUtc", Instant.now().toString()
            ));
        }
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getStatusCode().toString(),
                "message", ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                "timestampUtc", Instant.now().toString()
        ));
    }
}
