package com.bank.orchestrator.service;

import com.bank.ledger.contracts.enums.TransactionStatus;
import com.bank.orchestrator.dto.RiskScoreRequest;
import com.bank.orchestrator.dto.RiskScoreResponse;
import com.bank.orchestrator.dto.TransferInitiationRequest;
import com.bank.orchestrator.dto.TransferInitiationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final ObjectMapper objectMapper;

    public TransferOrchestrationService(
            IdempotencyLockService idempotencyService,
            CoolOffService coolOffService,
            BiometricChallengeService biometricService,
            RiskClientService riskService,
            CbsClientService cbsService,
            ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.coolOffService = coolOffService;
        this.biometricService = biometricService;
        this.riskService = riskService;
        this.cbsService = cbsService;
        this.objectMapper = objectMapper;
    }

    public TransferInitiationResponse initiateTransfer(TransferInitiationRequest request) {
        String txId = request.transactionId() != null && !request.transactionId().isBlank()
                ? request.transactionId()
                : UUID.randomUUID().toString();

        // 1. Idempotency Lock
        String idempKey = request.idempotencyKey() != null ? request.idempotencyKey() : txId;
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
                String challenge = biometricService.generateChallenge(txId);
                log.info("Transfer {} requires biometric authentication challenge", txId);
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
                // Authoritative CBS fund reservation (hold)
                cbsService.placeHold(request.sourceAccountId(), request.amount(), txId);

                String payloadJson = objectMapper.writeValueAsString(request);
                coolOffService.putInCoolOff(txId, payloadJson);
                log.info("Transfer {} queued into 10-minute cooling-off period with authoritative CBS hold", txId);
                return new TransferInitiationResponse(
                        txId,
                        TransactionStatus.Reserved,
                        request.amount(),
                        request.currency() != null ? request.currency() : "PHP",
                        request.sourceAccountId(),
                        request.destinationAccountId(),
                        "COOLING_OFF_PERIOD_INITIATED: High-value transaction locked for 10 minutes to protect against fraud. Authoritative fund hold placed in CBS.",
                        true,
                        600L,
                        false,
                        null,
                        Instant.now()
                );
            }

            // 5. Post to CBS
            return cbsService.postToCbs(request, txId, inCoolOff);

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            log.error("Transfer orchestration error for {}: {}", txId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Transfer execution failed: " + e.getMessage(), e);
        } finally {
            idempotencyService.releaseLock(idempKey);
        }
    }
}
