package com.bank.orchestrator.controller;

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

    @PostMapping
    public ResponseEntity<TransferInitiationResponse> initiateTransfer(
            @Valid @RequestBody TransferInitiationRequest request) {
        TransferInitiationResponse response = orchestrationService.initiateTransfer(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-biometric")
    public ResponseEntity<TransferInitiationResponse> verifyBiometric(
            @Valid @RequestBody BiometricVerificationRequest request) {
        boolean verified = biometricService.verifyChallenge(
                request.transactionId(),
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

        // Retrieve stored transfer from cooloff or execute
        String payloadJson = coolOffService.getCoolOffPayload(request.transactionId());
        if (payloadJson != null) {
            try {
                TransferInitiationRequest origReq = objectMapper.readValue(payloadJson, TransferInitiationRequest.class);
                TransferInitiationResponse resp = orchestrationService.initiateTransfer(origReq);
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError().build();
            }
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
        String payloadJson = coolOffService.getCoolOffPayload(request.transactionId());
        if (payloadJson != null) {
            try {
                TransferInitiationRequest origReq = objectMapper.readValue(payloadJson, TransferInitiationRequest.class);
                cbsService.releaseHold(origReq.sourceAccountId(), origReq.amount(), request.transactionId());
            } catch (Exception ignored) {
            }
        }
        boolean cancelled = coolOffService.cancelCoolOff(request.transactionId());
        return ResponseEntity.ok(Map.of(
                "transactionId", request.transactionId(),
                "cancelled", cancelled,
                "message", cancelled ? "Transfer cancelled successfully during cooling-off window. Funds hold released." : "Cooling-off window expired or not found"
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
