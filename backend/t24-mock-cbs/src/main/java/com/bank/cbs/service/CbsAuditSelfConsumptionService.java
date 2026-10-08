package com.bank.cbs.service;

import com.bank.cbs.config.KafkaConfig;
import com.bank.cbs.entity.audit.FailedTransactionAudit;
import com.bank.cbs.entity.audit.LedgerMutationAudit;
import com.bank.cbs.entity.audit.ReversalAudit;
import com.bank.cbs.entity.audit.TransactionStatusAudit;
import com.bank.cbs.repository.audit.FailedTransactionAuditRepository;
import com.bank.cbs.repository.audit.LedgerMutationAuditRepository;
import com.bank.cbs.repository.audit.ReversalAuditRepository;
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
    private final ReversalAuditRepository reversalAuditRepository;
    private final TransactionStatusAuditRepository transactionStatusAuditRepository;
    private final FailedTransactionAuditRepository failedTransactionAuditRepository;
    private final ObjectMapper objectMapper;

    public CbsAuditSelfConsumptionService(
            LedgerMutationAuditRepository ledgerMutationAuditRepository,
            ReversalAuditRepository reversalAuditRepository,
            TransactionStatusAuditRepository transactionStatusAuditRepository,
            FailedTransactionAuditRepository failedTransactionAuditRepository,
            ObjectMapper objectMapper) {
        this.ledgerMutationAuditRepository = ledgerMutationAuditRepository;
        this.reversalAuditRepository = reversalAuditRepository;
        this.transactionStatusAuditRepository = transactionStatusAuditRepository;
        this.failedTransactionAuditRepository = failedTransactionAuditRepository;
        this.objectMapper = objectMapper;
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

        log.info("Auditing TransferExecutedEvent into PostgreSQL audit vault: txId={}", txId);

        Optional<LedgerMutationAudit> lastAudit = ledgerMutationAuditRepository.findTopByOrderByAuditIdDesc();
        String prevHash = lastAudit.map(LedgerMutationAudit::getSha256Hash).orElse(GENESIS_HASH);

        Instant eventTime = event.getExecutedAtUtc() != null ? event.getExecutedAtUtc() : Instant.now();

        // Debit side record
        BigDecimal debitBefore = event.getSourceBalanceAfter().add(event.getAmount());
        String debitHash = calculateSha256(prevHash + "|" + txId + "|DEBIT|" + event.getAmount() + "|" + eventTime);

        LedgerMutationAudit debitAudit = LedgerMutationAudit.builder()
                .transactionId(txId + "-DR")
                .accountId(event.getSourceAccountId())
                .mutationType("DEBIT")
                .mutationAmount(event.getAmount())
                .beforeBalance(debitBefore)
                .afterBalance(event.getSourceBalanceAfter())
                .initiatorUserId("SYSTEM")
                .status("COMMITTED")
                .prevHash(prevHash)
                .sha256Hash(debitHash)
                .createdAt(eventTime)
                .build();
        ledgerMutationAuditRepository.save(debitAudit);

        // Credit side record
        BigDecimal creditBefore = event.getDestinationBalanceAfter().subtract(event.getAmount());
        String creditHash = calculateSha256(debitHash + "|" + txId + "|CREDIT|" + event.getAmount() + "|" + eventTime);

        LedgerMutationAudit creditAudit = LedgerMutationAudit.builder()
                .transactionId(txId + "-CR")
                .accountId(event.getDestinationAccountId())
                .mutationType("CREDIT")
                .mutationAmount(event.getAmount())
                .beforeBalance(creditBefore)
                .afterBalance(event.getDestinationBalanceAfter())
                .initiatorUserId("SYSTEM")
                .status("COMMITTED")
                .prevHash(debitHash)
                .sha256Hash(creditHash)
                .createdAt(eventTime)
                .build();
        ledgerMutationAuditRepository.save(creditAudit);
    }

    private void handleTransferReversed(TransferReversedEvent event) {
        if (event.getReversalTransactionId() != null && reversalAuditRepository.findByReversalTxId(event.getReversalTransactionId()).isPresent()) {
            log.info("Reversal audit for revTxId {} already processed, skipping duplicate event", event.getReversalTransactionId());
            return;
        }

        log.info("Auditing TransferReversedEvent into PostgreSQL audit vault: origTxId={}, revTxId={}",
                event.getOriginalTransactionId(), event.getReversalTransactionId());

        ReversalAudit audit = ReversalAudit.builder()
                .ticketId(event.getTicketId() != null ? event.getTicketId() : UUID.randomUUID().toString())
                .originalTxId(event.getOriginalTransactionId())
                .reversalTxId(event.getReversalTransactionId())
                .makerId(event.getMakerId())
                .checkerId(event.getCheckerId())
                .approvedAt(event.getExecutedAtUtc() != null ? event.getExecutedAtUtc() : Instant.now())
                .createdAt(Instant.now())
                .build();
        reversalAuditRepository.save(audit);
    }

    private void handleStatusChanged(TransactionStatusChangedEvent event) {
        Optional<TransactionStatusAudit> lastAudit = transactionStatusAuditRepository.findTopByOrderByAuditIdDesc();
        String prevHash = lastAudit.map(TransactionStatusAudit::getSha256Hash).orElse(GENESIS_HASH);

        Instant changeTime = event.getChangedAt() != null ? event.getChangedAt() : Instant.now();
        String hash = calculateSha256(prevHash + "|" + event.getTransactionId() + "|" + event.getToStatus() + "|" + changeTime);

        TransactionStatusAudit audit = TransactionStatusAudit.builder()
                .transactionId(event.getTransactionId())
                .fromStatus(event.getFromStatus() != null ? event.getFromStatus().name() : null)
                .toStatus(event.getToStatus() != null ? event.getToStatus().name() : null)
                .actorType(event.getActorType() != null ? event.getActorType().name() : "SYSTEM")
                .actorId(event.getActorId() != null ? event.getActorId() : "SYSTEM")
                .changeReason(event.getChangeReason() != null ? event.getChangeReason() : "STATUS_UPDATE")
                .reasonDetails(event.getReasonDetails())
                .changedAt(changeTime)
                .prevHash(prevHash)
                .sha256Hash(hash)
                .build();
        transactionStatusAuditRepository.save(audit);
    }

    private String calculateSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedhash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
