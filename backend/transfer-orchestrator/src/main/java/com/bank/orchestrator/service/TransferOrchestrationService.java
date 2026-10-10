package com.bank.orchestrator.service;

import com.bank.ledger.contracts.enums.TransactionStatus;
import com.bank.orchestrator.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class TransferOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(TransferOrchestrationService.class);
    private static final BigDecimal BIOMETRIC_THRESHOLD = new BigDecimal("50000.00");
    private static final BigDecimal COOL_OFF_THRESHOLD = new BigDecimal("250000.00");

    private final IdempotencyLockService idempotencyService;
    private final CoolOffService coolOffService;
    private final BiometricChallengeService biometricService;
    private final RiskClientService riskService;
    private final CbsClientService cbsService;
    private final InterbankWireClient wireClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public TransferOrchestrationService(
            IdempotencyLockService idempotencyService,
            CoolOffService coolOffService,
            BiometricChallengeService biometricService,
            RiskClientService riskService,
            CbsClientService cbsService,
            InterbankWireClient wireClient,
            ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.coolOffService = coolOffService;
        this.biometricService = biometricService;
        this.riskService = riskService;
        this.cbsService = cbsService;
        this.wireClient = wireClient;
        this.objectMapper = objectMapper;
    }

    public TransferOrchestrationService(
            IdempotencyLockService idempotencyService,
            CoolOffService coolOffService,
            BiometricChallengeService biometricService,
            RiskClientService riskService,
            CbsClientService cbsService,
            ObjectMapper objectMapper) {
        this(idempotencyService, coolOffService, biometricService, riskService, cbsService, null, objectMapper);
    }

    public TransferInitiationResponse initiateTransfer(TransferInitiationRequest request) {
        String rawTxId = request.transactionId();
        String txId;
        if (rawTxId == null || rawTxId.isBlank()) {
            txId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } else if (rawTxId.startsWith("TX-")) {
            txId = "TXN-" + rawTxId.substring(3);
        } else if (!rawTxId.startsWith("TXN-")) {
            txId = "TXN-" + rawTxId;
        } else {
            txId = rawTxId;
        }

        // 1. Idempotency Check & Lock
        String idempKey = request.idempotencyKey() != null ? request.idempotencyKey() : txId;
        java.util.Optional<String> cachedResp = idempotencyService.getCachedResponse(idempKey);
        if (cachedResp.isPresent()) {
            try {
                TransferInitiationResponse cached = objectMapper.readValue(cachedResp.get(), TransferInitiationResponse.class);
                log.info("Idempotent replay detected for key: {}. Returning cached response without duplicate posting.", idempKey);
                return new TransferInitiationResponse(
                        cached.transactionId(),
                        cached.status(),
                        cached.amount(),
                        cached.currency(),
                        cached.sourceAccountId(),
                        cached.destinationAccountId(),
                        "IDEMPOTENT_REPLAY: Duplicate transfer request safely intercepted without re-executing ledger mutations.",
                        cached.coolingOffRequired(),
                        cached.coolingOffExpiresInSeconds(),
                        cached.biometricRequired(),
                        cached.biometricChallenge(),
                        cached.processedAt()
                );
            } catch (Exception e) {
                log.warn("Failed to deserialize cached idempotency response: {}", e.getMessage());
            }
        }

        if (!idempotencyService.acquireLock(idempKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent or duplicate transfer request in progress");
        }

        try {
            // 2. Risk Evaluation
            RiskScoreRequest riskReq = new RiskScoreRequest(
                    txId,
                    request.sourceAccountId(),
                    request.destinationAccountId(),
                    request.amount(),
                    request.currency() != null ? request.currency() : "PHP",
                    request.deviceId()
            );
            RiskScoreResponse riskResp = riskService.evaluateRisk(riskReq);

            if ("BLOCK".equalsIgnoreCase(riskResp.decision())) {
                log.warn("Transfer {} blocked by Fraud Engine: {}", txId, riskResp.riskReason());
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Transaction could not be processed at this time. Please contact customer support.");
            }

            if ("ADVISORY_WARNING".equalsIgnoreCase(riskResp.decision()) && !request.scamAdvisoryAcknowledged()) {
                log.info("Transfer {} requires scam advisory acknowledgement: {}", txId, riskResp.riskReason());
                return new TransferInitiationResponse(
                        txId,
                        TransactionStatus.Processing,
                        request.amount(),
                        request.currency() != null ? request.currency() : "PHP",
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        "SCAM_ADVISORY_WARNING: Potential high-risk payee detected. " + riskResp.riskReason() + ". Please confirm acknowledgment.",
                        false,
                        0L,
                        false,
                        null,
                        Instant.now()
                );
            }

            // 3. Biometric Verification Challenge (High Value or Moderate Risk)
            boolean requiresBiometric = request.amount().compareTo(BIOMETRIC_THRESHOLD) >= 0 || riskResp.score() >= 60;
            if (requiresBiometric && (request.biometricSignature() == null || request.biometricSignature().isBlank())) {
                String challenge = biometricService.generateChallenge(
                        txId,
                        request.destinationAccountId(),
                        request.amount(),
                        request.currency() != null ? request.currency() : "PHP"
                );
                log.info("Transfer {} requires biometric authentication challenge", txId);
                try {
                    coolOffService.putInCoolOff("bio:pending:" + txId, objectMapper.writeValueAsString(request));
                } catch (Exception e) {
                    log.warn("Failed to cache pending biometric request: {}", e.getMessage());
                }
                return new TransferInitiationResponse(
                        txId,
                        TransactionStatus.Authorized,
                        request.amount(),
                        request.currency() != null ? request.currency() : "PHP",
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        "BIOMETRIC_CHALLENGE_REQUIRED: Device biometric verification required for transaction.",
                        false,
                        0L,
                        true,
                        challenge,
                        Instant.now()
                );
            }

            // 4. Anti-Scam Cooling-off Period (Threshold >= ₱250,000)
            boolean inCoolOff = coolOffService.isInCoolOff(txId);
            if (request.amount().compareTo(COOL_OFF_THRESHOLD) >= 0 && !inCoolOff) {
                String payloadJson = objectMapper.writeValueAsString(request);
                coolOffService.putInCoolOff(txId, payloadJson);
                log.info("Transfer {} queued into 10-minute cooling-off period", txId);
                return new TransferInitiationResponse(
                        txId,
                        TransactionStatus.Reserved,
                        request.amount(),
                        request.currency() != null ? request.currency() : "PHP",
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        "COOLING_OFF_PERIOD_INITIATED: High-value transaction locked for 10 minutes to protect against fraud.",
                        true,
                        600L,
                        false,
                        null,
                        Instant.now()
                );
            }

            // 5. Inter-bank check (Nostro/Vostro Corridor)
            boolean isInterbank = request.destinationBank() != null &&
                    !request.destinationBank().isBlank() &&
                    !"AURABANK".equalsIgnoreCase(request.destinationBank());

            TransferInitiationResponse finalResp;
            if (isInterbank) {
                finalResp = processInterbankTransfer(request, txId, inCoolOff);
            } else {
                // Normal Intra-bank Transfer on CBS
                finalResp = cbsService.postToCbs(request, txId, inCoolOff);
            }

            // Cache response for future idempotent replays
            try {
                idempotencyService.cacheResponse(idempKey, objectMapper.writeValueAsString(finalResp));
            } catch (Exception e) {
                log.warn("Failed to cache idempotency response: {}", e.getMessage());
            }

            // Real-Time Cache Eviction on Mutation
            idempotencyService.evictBalanceCache(request.sourceAccountId(), request.destinationAccountId());

            return finalResp;

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            log.error("Transfer orchestration error for {}: {}", txId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Transfer execution failed: " + e.getMessage(), e);
        } finally {
            idempotencyService.releaseLock(idempKey);
        }
    }

    /**
     * Executes Outward Inter-Bank Transfer via Bilateral Nostro/Vostro Model.
     * Leg 1: Debits Sender and Credits ACC-NOSTRO-BANK-B in local CBS.
     * Leg 2: Dispatches HTTP Wire to Partner Bank.
     * Saga: If Leg 2 fails, automatically executes Compensating Reversal on CBS.
     */
    private TransferInitiationResponse processInterbankTransfer(
            TransferInitiationRequest request, String txId, boolean inCoolOff) {
        log.info("Starting Outward Inter-bank Transfer via Nostro buffer: txId={}, targetBank={}, beneficiary={}",
                txId, request.destinationBank(), request.destinationAccountId());

        // Leg 1: CBS Local Mutation (Debit Sender -> Credit ACC-NOSTRO-BANK-B)
        TransferInitiationRequest leg1Req = new TransferInitiationRequest(
                txId,
                request.sourceAccountId(),
                "ACC-NOSTRO-BANK-B",
                request.amount(),
                request.currency() != null ? request.currency() : "PHP",
                "Interbank Wire Leg 1 to " + request.destinationBank() + ": " + request.destinationAccountId(),
                request.deviceId(),
                request.idempotencyKey(),
                request.biometricSignature(),
                request.scamAdvisoryAcknowledged(),
                "AURABANK",
                request.senderName(),
                null
        );
        cbsService.postToCbs(leg1Req, txId, inCoolOff);
        log.info("Interbank Leg 1 committed on CBS: {} debited into ACC-NOSTRO-BANK-B", request.sourceAccountId());

        // Leg 2: Outward HTTP Wire Dispatch
        InterbankWireRequest wireReq = new InterbankWireRequest(
                txId,
                "AURABANK",
                request.senderName() != null ? request.senderName() : "AuraBank Customer",
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.currency() != null ? request.currency() : "PHP",
                Instant.now().toString()
        );

        try {
            InterbankWireResponse wireResp = wireClient.dispatchWire(wireReq, request.simulationMode());
            if (wireResp != null && "SETTLED".equalsIgnoreCase(wireResp.status())) {
                log.info("Interbank transfer settled by partner: txId={}, partnerRef={}", txId, wireResp.partnerReference());
                return new TransferInitiationResponse(
                        txId,
                        TransactionStatus.Posted,
                        request.amount(),
                        request.currency() != null ? request.currency() : "PHP",
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        "Inter-bank transfer executed and settled by " + request.destinationBank() + " (Partner Ref: " + wireResp.partnerReference() + ")",
                        false,
                        0L,
                        false,
                        null,
                        Instant.now()
                );
            } else {
                throw new IllegalStateException("Partner bank rejected wire settlement: " + (wireResp != null ? wireResp.message() : "Unknown"));
            }
        } catch (Exception e) {
            log.warn("Interbank Leg 2 wire failed or timed out: {}. Triggering automated Saga Compensating Reversal...", e.getMessage());
            cbsService.executeCompensatingReversal(txId, "INTERBANK_WIRE_FAILURE: " + e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Inter-bank transfer failed at partner bank; funds refunded via automated Saga rollback: " + e.getMessage());
        }
    }
}
