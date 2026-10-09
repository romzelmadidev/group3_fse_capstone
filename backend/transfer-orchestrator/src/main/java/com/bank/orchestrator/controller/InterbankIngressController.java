package com.bank.orchestrator.controller;

import com.bank.orchestrator.dto.InterbankWireRequest;
import com.bank.orchestrator.dto.InterbankWireResponse;
import com.bank.orchestrator.dto.TransferInitiationRequest;
import com.bank.orchestrator.service.CbsClientService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Handles inbound inter-bank wires from Partner Bank (Group B)
 * and hosts the local Partner Bank Test Simulator.
 */
@RestController
@RequestMapping("/api/v1")
public class InterbankIngressController {

    private static final Logger log = LoggerFactory.getLogger(InterbankIngressController.class);

    private final CbsClientService cbsService;
    private final String expectedApiKey;

    public InterbankIngressController(
            CbsClientService cbsService,
            @Value("${services.partner.api-key:AURABANK-SECURE-KEY-2026}") String expectedApiKey) {
        this.cbsService = cbsService;
        this.expectedApiKey = expectedApiKey;
    }

    /**
     * Inward Inter-Bank Wire Endpoint (Bank B -> AuraBank).
     * Leg 3 on AuraBank: Debits ACC-NOSTRO-BANK-B and credits the local beneficiary.
     */
    @PostMapping("/interbank/transfers/inward")
    public ResponseEntity<InterbankWireResponse> handleInwardWire(
            @RequestHeader(value = "X-Interbank-API-Key", required = false) String apiKey,
            @Valid @RequestBody InterbankWireRequest request) {

        log.info("Received Inward Inter-Bank Wire: refId={}, fromBank={}, sender={}, beneficiary={}, amount={}",
                request.referenceId(), request.originatingBank(), request.senderAccountId(), request.beneficiaryAccountId(), request.amount());

        // 1. Verify Partner API Key if configured
        if (expectedApiKey != null && !expectedApiKey.isBlank() && apiKey != null && !expectedApiKey.equals(apiKey)) {
            log.warn("Unauthorized inter-bank wire rejected: invalid API key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new InterbankWireResponse("FAILED", null, Instant.now().toString(), "Invalid X-Interbank-API-Key"));
        }

        // 2. Validate Beneficiary Account Exists in AuraBank
        Map<String, String> balance = cbsService.getAccountBalance(request.beneficiaryAccountId());
        if (balance == null || balance.isEmpty() || "0.00".equals(balance.get("availableBalance")) && !balance.containsKey("accountNumber")) {
            log.warn("Inward wire failed: Beneficiary account {} not found in AuraBank", request.beneficiaryAccountId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new InterbankWireResponse("FAILED", null, Instant.now().toString(), "Beneficiary account not found in AuraBank"));
        }

        // 3. Leg 3 Core Mutation: Debit ACC-NOSTRO-BANK-B -> Credit Beneficiary Account
        String inwardTxId = "INW-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            TransferInitiationRequest inwardReq = new TransferInitiationRequest(
                    inwardTxId,
                    "ACC-NOSTRO-BANK-B",
                    request.beneficiaryAccountId(),
                    request.amount(),
                    request.currency() != null ? request.currency() : "PHP",
                    "Inward Interbank Wire from " + request.originatingBank() + " (Ref: " + request.referenceId() + ")",
                    "INTERBANK_GATEWAY",
                    request.referenceId(),
                    null,
                    false,
                    "AURABANK",
                    request.senderName(),
                    null
            );
            cbsService.postToCbs(inwardReq, inwardTxId, false);
            log.info("Inward wire committed: {} credited with amount {}", request.beneficiaryAccountId(), request.amount());

            return ResponseEntity.ok(new InterbankWireResponse(
                    "SETTLED",
                    "AURA-" + inwardTxId,
                    Instant.now().toString(),
                    "Inward wire posted and settled successfully"
            ));

        } catch (Exception e) {
            log.error("Failed to post inward wire to CBS: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new InterbankWireResponse("FAILED", null, Instant.now().toString(), "CBS Posting Error: " + e.getMessage()));
        }
    }

    /**
     * Local Partner Bank Simulator Endpoint.
     * Allows our team to test outbound wires offline with 3 toggleable modes:
     * - Default: Immediate SETTLED response
     * - TIMEOUT: Delays 5s to trigger our client timeout and verify automated Saga rollback
     * - REJECT: Returns 400 error to verify automated Saga rollback on rejection
     */
    @PostMapping("/mock-partner/transfers")
    public ResponseEntity<InterbankWireResponse> simulatePartnerBank(
            @RequestParam(value = "simulationMode", required = false) String simulationMode,
            @RequestBody InterbankWireRequest request) {

        log.info("Local Partner Bank Simulator received wire: mode={}, refId={}, beneficiary={}, amount={}",
                simulationMode, request.referenceId(), request.beneficiaryAccountId(), request.amount());

        if ("TIMEOUT".equalsIgnoreCase(simulationMode)) {
            try {
                log.info("Partner Simulator simulating 5.5s network lag to exercise Saga rollback...");
                Thread.sleep(5500);
            } catch (InterruptedException ignored) {}
        }

        if ("REJECT".equalsIgnoreCase(simulationMode)) {
            log.warn("Partner Simulator simulating rejection (Beneficiary Account Not Found)");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new InterbankWireResponse("REJECTED", null, Instant.now().toString(), "BENEFICIARY_ACCOUNT_NOT_FOUND"));
        }

        // Happy Path: Success settlement
        String partnerRef = "B-TXN-" + System.currentTimeMillis();
        return ResponseEntity.ok(new InterbankWireResponse(
                "SETTLED",
                partnerRef,
                Instant.now().toString(),
                "Wire settled in Partner Bank CBS"
        ));
    }
}
