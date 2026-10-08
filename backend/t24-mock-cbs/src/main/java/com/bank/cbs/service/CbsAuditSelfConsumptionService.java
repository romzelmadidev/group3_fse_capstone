package com.bank.cbs.service;

import com.bank.cbs.config.KafkaConfig;
import com.bank.cbs.entity.audit.FailedTransactionAudit;
import com.bank.cbs.entity.audit.LedgerMutationAudit;
import com.bank.cbs.entity.audit.TransactionStatusAudit;
import com.bank.cbs.repository.audit.FailedTransactionAuditRepository;
import com.bank.cbs.repository.audit.LedgerMutationAuditRepository;
import com.bank.cbs.repository.audit.TransactionStatusAuditRepository;
import com.bank.ledger.contracts.dto.events.TransactionStatusChangedEvent;
import com.bank.ledger.contracts.dto.events.TransferExecutedEvent;
import com.bank.ledger.contracts.dto.events.TransferFailedToDlqEvent;
import com.bank.ledger.contracts.dto.events.TransferReversedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class CbsAuditSelfConsumptionService {

    private static final Logger log = LoggerFactory.getLogger(CbsAuditSelfConsumptionService.class);
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final LedgerMutationAuditRepository ledgerMutationAuditRepository;
    private final TransactionStatusAuditRepository transactionStatusAuditRepository;
    private final FailedTransactionAuditRepository failedTransactionAuditRepository;
    private final ObjectMapper objectMapper;
    private final MerkleTreeService merkleTreeService;

    public CbsAuditSelfConsumptionService(
            LedgerMutationAuditRepository ledgerMutationAuditRepository,
            TransactionStatusAuditRepository transactionStatusAuditRepository,
            FailedTransactionAuditRepository failedTransactionAuditRepository,
            ObjectMapper objectMapper,
            MerkleTreeService merkleTreeService) {
        this.ledgerMutationAuditRepository = ledgerMutationAuditRepository;
        this.transactionStatusAuditRepository = transactionStatusAuditRepository;
        this.failedTransactionAuditRepository = failedTransactionAuditRepository;
        this.objectMapper = objectMapper;
        this.merkleTreeService = merkleTreeService;
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_TRANSFERS_EVENTS, groupId = "cbs-audit-workers")
    @Transactional("auditTransactionManager")
    public void consumeTransfersEvent(org.apache.kafka.clients.consumer.ConsumerRecord<String, Object> record) {
        try {
            Object raw = record != null ? record.value() : null;
            if (raw == null) return;
            String messagePayload = raw instanceof String str ? str : objectMapper.writeValueAsString(raw);
            JsonNode root = objectMapper.readTree(messagePayload);
            if (root.has("sourceBalanceAfter")) {
                TransferExecutedEvent event = objectMapper.treeToValue(root, TransferExecutedEvent.class);
                handleTransferExecuted(event);
            } else if (root.has("originalTransactionId") && root.has("reversalTransactionId")) {
                TransferReversedEvent event = objectMapper.treeToValue(root, TransferReversedEvent.class);
                handleTransferReversed(event);
            } else if (root.has("toStatus") && root.has("actorType")) {
                TransactionStatusChangedEvent event = objectMapper.treeToValue(root, TransactionStatusChangedEvent.class);
                handleStatusChanged(event);
            } else {
                log.debug("Ignored generic transfer event: {}", messagePayload);
            }
        } catch (Exception e) {
            log.error("Failed to process audit event from transfers topic: {}", e.getMessage(), e);
        }
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_TRANSFERS_DLQ, groupId = "cbs-audit-workers")
    @Transactional("auditTransactionManager")
    public void consumeDlqEvent(String messagePayload) {
        try {
            TransferFailedToDlqEvent event = objectMapper.readValue(messagePayload, TransferFailedToDlqEvent.class);
            log.warn("Logging failed transfer to audit vault from DLQ: transferId={}, errorType={}",
                    event.getTransactionId(), event.getErrorType());

            FailedTransactionAudit audit = FailedTransactionAudit.builder()
                    .incidentId(event.getIncidentId() != null ? event.getIncidentId() : UUID.randomUUID().toString())
                    .correlationId(event.getCorrelationId() != null ? event.getCorrelationId() : UUID.randomUUID().toString())
                    .transactionId(event.getTransactionId())
                    .errorType(event.getErrorType() != null ? event.getErrorType() : "SYSTEM_FAULT")
                    .errorCode(event.getErrorCode() != null ? event.getErrorCode() : "TRANSFER_FAILED")
                    .circuitBreakerState(event.getCircuitBreakerState() != null ? event.getCircuitBreakerState() : "OPEN")
                    .payloadJson(objectMapper.writeValueAsString(event.getOriginalPayload()))
                    .stackTrace(event.getStackTrace())
                    .replayStatus("PENDING_REPLAY")
                    .failureTimestamp(event.getFailureTimestampUtc() != null ? event.getFailureTimestampUtc() : Instant.now())
                    .build();
            failedTransactionAuditRepository.save(audit);
        } catch (Exception e) {
            log.error("Failed to process DLQ event in audit worker: {}", e.getMessage(), e);
        }
    }

    private void handleTransferExecuted(TransferExecutedEvent event) {
        String txId = event.getTransactionId();
        if (txId != null && ledgerMutationAuditRepository.findByTransactionId(txId + "-DR").isPresent()) {
            log.info("Ledger mutation audit for txId {} already processed, skipping duplicate event", txId);
            return;
        }

        log.info("Auditing TransferExecutedEvent into PostgreSQL audit vault via Merkle leaf: txId={}", txId);

        Instant eventTime = event.getExecutedAtUtc() != null ? event.getExecutedAtUtc() : Instant.now();

        // Debit side record (concurrently calculated leaf hash with zero table locking)
        BigDecimal debitBefore = event.getSourceBalanceAfter().add(event.getAmount());
        String debitPayload = txId + "-DR|" + event.getSourceAccountId() + "|DEBIT|" + event.getAmount() + "|" + debitBefore + "|" + event.getSourceBalanceAfter() + "|" + eventTime;
        String debitHash = merkleTreeService.calculateSha256(debitPayload);

        LedgerMutationAudit debitAudit = LedgerMutationAudit.builder()
                .transactionId(txId + "-DR")
                .accountId(event.getSourceAccountId())
                .mutationType("DEBIT")
                .mutationAmount(event.getAmount())
                .beforeBalance(debitBefore)
                .afterBalance(event.getSourceBalanceAfter())
                .initiatorUserId("SYSTEM")
                .status("COMMITTED")
                .prevHash("MERKLE_LEAF")
                .sha256Hash(debitHash)
                .createdAt(eventTime)
                .build();
        ledgerMutationAuditRepository.save(debitAudit);

        // Credit side record (concurrently calculated leaf hash with zero table locking)
        BigDecimal creditBefore = event.getDestinationBalanceAfter().subtract(event.getAmount());
        String creditPayload = txId + "-CR|" + event.getDestinationAccountId() + "|CREDIT|" + event.getAmount() + "|" + creditBefore + "|" + event.getDestinationBalanceAfter() + "|" + eventTime;
        String creditHash = merkleTreeService.calculateSha256(creditPayload);

        LedgerMutationAudit creditAudit = LedgerMutationAudit.builder()
                .transactionId(txId + "-CR")
                .accountId(event.getDestinationAccountId())
                .mutationType("CREDIT")
                .mutationAmount(event.getAmount())
                .beforeBalance(creditBefore)
                .afterBalance(event.getDestinationBalanceAfter())
                .initiatorUserId("SYSTEM")
                .status("COMMITTED")
                .prevHash("MERKLE_LEAF")
                .sha256Hash(creditHash)
                .createdAt(eventTime)
                .build();
        ledgerMutationAuditRepository.save(creditAudit);
    }

    private void handleTransferReversed(TransferReversedEvent event) {
        String revTxId = event.getReversalTransactionId();
        if (revTxId != null && !ledgerMutationAuditRepository.findByTransactionId(revTxId).isEmpty()) {
            log.info("Reversal audit for revTxId {} already processed, skipping duplicate event", revTxId);
            return;
        }

        log.info("Auditing TransferReversedEvent into PostgreSQL audit vault via Merkle leaf: origTxId={}, revTxId={}",
                event.getOriginalTransactionId(), revTxId);

        Instant eventTime = event.getExecutedAtUtc() != null ? event.getExecutedAtUtc() : Instant.now();

        // Beneficiary debit leg (reclaiming disbursed funds)
        if (event.getBeneficiaryAccountId() != null && event.getBeneficiaryBalanceAfter() != null) {
            BigDecimal beforeBal = event.getBeneficiaryBalanceAfter().add(event.getAmount());
            String payload = revTxId + "|" + event.getBeneficiaryAccountId() + "|REVERSAL|" + event.getAmount() + "|" + beforeBal + "|" + event.getBeneficiaryBalanceAfter() + "|" + eventTime;
            String leafHash = merkleTreeService.calculateSha256(payload);

            LedgerMutationAudit debitLeg = LedgerMutationAudit.builder()
                    .transactionId(revTxId)
                    .accountId(event.getBeneficiaryAccountId())
                    .mutationType("REVERSAL")
                    .mutationAmount(event.getAmount())
                    .beforeBalance(beforeBal)
                    .afterBalance(event.getBeneficiaryBalanceAfter())
                    .initiatorUserId(event.getMakerId() != null ? event.getMakerId() : "SYSTEM")
                    .approvedByUserId(event.getCheckerId())
                    .status("COMMITTED")
                    .prevHash("MERKLE_LEAF")
                    .sha256Hash(leafHash)
                    .createdAt(eventTime)
                    .build();
            ledgerMutationAuditRepository.save(debitLeg);
        }

        // Original sender credit leg (restoring funds back to original sender)
        if (event.getOriginalSenderAccountId() != null && event.getOriginalSenderBalanceAfter() != null) {
            BigDecimal beforeBal = event.getOriginalSenderBalanceAfter().subtract(event.getAmount());
            String payload = revTxId + "|" + event.getOriginalSenderAccountId() + "|REVERSAL|" + event.getAmount() + "|" + beforeBal + "|" + event.getOriginalSenderBalanceAfter() + "|" + eventTime;
            String leafHash = merkleTreeService.calculateSha256(payload);

            LedgerMutationAudit creditLeg = LedgerMutationAudit.builder()
                    .transactionId(revTxId)
                    .accountId(event.getOriginalSenderAccountId())
                    .mutationType("REVERSAL")
                    .mutationAmount(event.getAmount())
                    .beforeBalance(beforeBal)
                    .afterBalance(event.getOriginalSenderBalanceAfter())
                    .initiatorUserId(event.getMakerId() != null ? event.getMakerId() : "SYSTEM")
                    .approvedByUserId(event.getCheckerId())
                    .status("COMMITTED")
                    .prevHash("MERKLE_LEAF")
                    .sha256Hash(leafHash)
                    .createdAt(eventTime)
                    .build();
            ledgerMutationAuditRepository.save(creditLeg);
        }
    }

    private void handleStatusChanged(TransactionStatusChangedEvent event) {
        Instant changeTime = event.getChangedAt() != null ? event.getChangedAt() : Instant.now();
        String payload = event.getTransactionId() + "|" + event.getToStatus() + "|" + event.getChangeReason() + "|" + changeTime;
        String hash = merkleTreeService.calculateSha256(payload);

        TransactionStatusAudit audit = TransactionStatusAudit.builder()
                .transactionId(event.getTransactionId())
                .fromStatus(event.getFromStatus() != null ? event.getFromStatus().name() : null)
                .toStatus(event.getToStatus() != null ? event.getToStatus().name() : null)
                .actorType(event.getActorType() != null ? event.getActorType().name() : "SYSTEM")
                .actorId(event.getActorId() != null ? event.getActorId() : "SYSTEM")
                .changeReason(event.getChangeReason() != null ? event.getChangeReason() : "STATUS_UPDATE")
                .reasonDetails(event.getReasonDetails())
                .changedAt(changeTime)
                .prevHash("MERKLE_LEAF")
                .sha256Hash(hash)
                .build();
        transactionStatusAuditRepository.save(audit);
    }

    private String calculateSha256(String input) {
        return merkleTreeService.calculateSha256(input);
    }
}
