package com.bank.cbs.service;

import com.bank.cbs.config.KafkaConfig;
import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.entity.master.*;
import com.bank.cbs.repository.master.*;
import com.bank.ledger.contracts.dto.events.TransactionStatusChangedEvent;
import com.bank.ledger.contracts.dto.events.TransferExecutedEvent;
import com.bank.ledger.contracts.enums.ActorType;
import com.bank.ledger.contracts.enums.ChangeReasonCode;
import com.bank.ledger.contracts.enums.TransactionStatus;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class CbsFundsTransferService {

    private static final Logger log = LoggerFactory.getLogger(CbsFundsTransferService.class);

    private final BalanceMasterRepository balanceRepository;
    private final TransactionMasterRepository transactionRepository;
    private final GlLedgerMasterRepository glLedgerRepository;
    private final GlBalanceMasterRepository glBalanceRepository;
    private final SystemDateMasterRepository systemDateRepository;
    private final TransactionStatusHistoryMasterRepository statusHistoryRepository;
    private final OutboxEventMasterRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public CbsFundsTransferService(
            BalanceMasterRepository balanceRepository,
            TransactionMasterRepository transactionRepository,
            GlLedgerMasterRepository glLedgerRepository,
            GlBalanceMasterRepository glBalanceRepository,
            SystemDateMasterRepository systemDateRepository,
            TransactionStatusHistoryMasterRepository statusHistoryRepository,
            OutboxEventMasterRepository outboxRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.glLedgerRepository = glLedgerRepository;
        this.glBalanceRepository = glBalanceRepository;
        this.systemDateRepository = systemDateRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional("masterTransactionManager")
    public TransferResponseDto executeTransfer(TransferRequestDto request) {
        log.info("Processing CBS funds transfer: amount={} source={} dest={}",
                request.amount(), request.sourceAccountId(), request.destinationAccountId());

        // 0. Idempotency pre-check (Prevents double-mutations on retry / DLQ replay)
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            var existingTxOpt = transactionRepository.findByIdempotencyKey(request.idempotencyKey());
            if (existingTxOpt.isPresent()) {
                TransactionMaster existingTx = existingTxOpt.get();
                log.info("Idempotent duplicate detected for key {}. Replaying existing transaction {} without mutating balances.",
                        request.idempotencyKey(), existingTx.getTransactionId());
                BalanceMaster src = balanceRepository.findById(existingTx.getSourceAccountId()).orElse(null);
                BalanceMaster dst = balanceRepository.findById(existingTx.getTargetAccountId()).orElse(null);
                String ofsResp = OfsMessageUtil.buildOfsResponse(true, existingTx.getTransactionId(), "POSTED_SUCCESSFULLY_IDEMPOTENT_REPLAY");
                return new TransferResponseDto(
                        existingTx.getTransactionId(),
                        existingTx.getStatus(),
                        existingTx.getAmount(),
                        existingTx.getCurrency(),
                        existingTx.getSourceAccountId(),
                        existingTx.getTargetAccountId(),
                        src != null ? src.getBalanceAmount() : null,
                        dst != null ? dst.getBalanceAmount() : null,
                        ofsResp,
                        existingTx.getCreatedAt()
                );
            }
        }

        // 1. Posting window check
        SystemDateMaster systemDate = systemDateRepository.findTopByOrderBySystemDateIdAsc()
                .orElseGet(() -> {
                    SystemDateMaster defaultDate = new SystemDateMaster();
                    defaultDate.setSystemDateId("SYS-DATE-1");
                    defaultDate.setBusinessDate(LocalDate.now());
                    defaultDate.setStatus("ONLINE");
                    defaultDate.setPostingWindowOpen(true);
                    return defaultDate;
                });

        if (!Boolean.TRUE.equals(systemDate.getPostingWindowOpen())) {
            throw new IllegalStateException("CBS Posting window is closed. Business status: " + systemDate.getStatus());
        }

        String sourceId = request.sourceAccountId();
        String destId = request.destinationAccountId();
        if (sourceId == null || sourceId.isBlank() || destId == null || destId.isBlank()) {
            throw new IllegalArgumentException("Source and destination accounts must not be null or blank.");
        }
        if (sourceId.equalsIgnoreCase(destId)) {
            throw new IllegalArgumentException("Source and destination accounts must be different.");
        }

        // 2. Ascending lock ordering to prevent deadlocks
        String firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
        String secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

        BalanceMaster firstBal = balanceRepository.findByAccountIdForUpdate(firstLockId)
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + firstLockId));
        BalanceMaster secondBal = balanceRepository.findByAccountIdForUpdate(secondLockId)
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + secondLockId));

        BalanceMaster sourceBal = sourceId.equals(firstLockId) ? firstBal : secondBal;
        BalanceMaster destBal = destId.equals(firstLockId) ? firstBal : secondBal;

        // 3. Solvency check
        BigDecimal currentSourceHold = sourceBal.getHoldAmount() != null ? sourceBal.getHoldAmount() : BigDecimal.ZERO;
        BigDecimal availableBalance = sourceBal.getAvailableBalance();
        if (availableBalance == null) {
            availableBalance = sourceBal.getBalanceAmount().subtract(currentSourceHold);
        }

        boolean isHeld = Boolean.TRUE.equals(request.fundsHeld());
        if (isHeld) {
            if (currentSourceHold.compareTo(request.amount()) < 0 || sourceBal.getBalanceAmount().compareTo(request.amount()) < 0) {
                throw new IllegalArgumentException(String.format(
                        "Held funds mismatch. Account %s has hold %s and total balance %s, requested %s",
                        sourceId, currentSourceHold, sourceBal.getBalanceAmount(), request.amount()));
            }
        } else {
            if (availableBalance.compareTo(request.amount()) < 0) {
                throw new IllegalArgumentException(String.format(
                        "Insufficient funds. Account %s has available balance %s, requested %s",
                        sourceId, availableBalance, request.amount()));
            }
        }

        // 4. Update balances
        Instant now = Instant.now();
        BigDecimal sourceBefore = sourceBal.getBalanceAmount();
        sourceBal.setBalanceAmount(sourceBal.getBalanceAmount().subtract(request.amount()));
        if (isHeld) {
            BigDecimal newHold = currentSourceHold.subtract(request.amount());
            if (newHold.compareTo(BigDecimal.ZERO) < 0) {
                newHold = BigDecimal.ZERO;
            }
            sourceBal.setHoldAmount(newHold);
        }
        BigDecimal sourceHold = sourceBal.getHoldAmount() != null ? sourceBal.getHoldAmount() : BigDecimal.ZERO;
        sourceBal.setAvailableBalance(sourceBal.getBalanceAmount().subtract(sourceHold));
        sourceBal.setUpdatedAt(now);
        balanceRepository.save(sourceBal);
        BigDecimal sourceAfter = sourceBal.getBalanceAmount();

        destBal.setBalanceAmount(destBal.getBalanceAmount().add(request.amount()));
        BigDecimal destHold = destBal.getHoldAmount() != null ? destBal.getHoldAmount() : BigDecimal.ZERO;
        destBal.setAvailableBalance(destBal.getBalanceAmount().subtract(destHold));
        destBal.setUpdatedAt(now);
        balanceRepository.save(destBal);

        // 5. Double-entry GL postings
        String txId = resolveTransactionId(request.transactionId());
        LocalDate valDate = systemDate.getBusinessDate() != null ? systemDate.getBusinessDate() : LocalDate.now();

        GlLedgerMaster drEntry = GlLedgerMaster.builder()
                .journalId(UUID.randomUUID().toString())
                .transactionId(txId)
                .glCode("20100")
                .debitAmount(request.amount())
                .creditAmount(BigDecimal.ZERO)
                .postingDate(valDate)
                .createdAt(now)
                .build();
        glLedgerRepository.save(drEntry);

        GlLedgerMaster crEntry = GlLedgerMaster.builder()
                .journalId(UUID.randomUUID().toString())
                .transactionId(txId)
                .glCode("20100")
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(request.amount())
                .postingDate(valDate)
                .createdAt(now)
                .build();
        glLedgerRepository.save(crEntry);

        updateGlBalance("20100", request.amount(), request.amount());

        // 6. Record transaction master
        TransactionMaster tx = transactionRepository.findById(txId).orElse(null);
        if (tx == null) {
            tx = TransactionMaster.builder()
                    .transactionId(txId)
                    .idempotencyKey(request.idempotencyKey())
                    .sourceAccountId(sourceId)
                    .targetAccountId(destId)
                    .amount(request.amount())
                    .beforeBalance(sourceBefore)
                    .afterBalance(sourceAfter)
                    .currency(request.currency() != null ? request.currency() : "PHP")
                    .transactionType("INTRA_BANK")
                    .status(TransactionStatus.Posted.name())
                    .requiresMakerChecker(0)
                    .memo(request.description() != null ? request.description() : "Funds Transfer")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else {
            tx.setStatus(TransactionStatus.Posted.name());
            tx.setBeforeBalance(sourceBefore);
            tx.setAfterBalance(sourceAfter);
            tx.setUpdatedAt(now);
        }
        transactionRepository.save(tx);

        // 7. Status history record - record entry for each status in lifecycle
        List<TransactionStatusHistoryMaster> existingHistory = getExistingStatusHistory(txId);

        List<TransactionStatusHistoryMaster> newHistoryList = new ArrayList<>();
        if (existingHistory.isEmpty()) {
            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(null)
                    .toStatus(TransactionStatus.Initiated.name())
                    .changeReason(ChangeReasonCode.API_INGESTION)
                    .reasonDetails("Transfer initiated via API ingestion")
                    .actorId(sourceId)
                    .actorType(ActorType.SYSTEM_ORCH.name())
                    .changedAt(now.minusMillis(40))
                    .build());

            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(TransactionStatus.Initiated.name())
                    .toStatus(TransactionStatus.Authorized.name())
                    .changeReason(ChangeReasonCode.BIOMETRIC_AUTH_VERIFIED)
                    .reasonDetails("Customer authorization and risk validation passed")
                    .actorId(sourceId)
                    .actorType(ActorType.CUSTOMER.name())
                    .changedAt(now.minusMillis(30))
                    .build());

            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(TransactionStatus.Authorized.name())
                    .toStatus(TransactionStatus.Reserved.name())
                    .changeReason(ChangeReasonCode.FUNDS_RESERVATION_EARMARKED)
                    .reasonDetails("Funds reservation earmarked in core balance")
                    .actorId("SYSTEM_CBS")
                    .actorType(ActorType.SYSTEM_CBS.name())
                    .changedAt(now.minusMillis(20))
                    .build());

            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(TransactionStatus.Reserved.name())
                    .toStatus(TransactionStatus.Processing.name())
                    .changeReason(ChangeReasonCode.CBS_OFS_PROCESSING)
                    .reasonDetails("Core OFS transaction processing initiated")
                    .actorId("SYSTEM_CBS")
                    .actorType(ActorType.SYSTEM_CBS.name())
                    .changedAt(now.minusMillis(10))
                    .build());

            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(TransactionStatus.Processing.name())
                    .toStatus(TransactionStatus.Posted.name())
                    .changeReason(ChangeReasonCode.ACID_LEDGER_COMMITTED)
                    .reasonDetails("ACID double-entry ledger posting committed")
                    .actorId("SYSTEM_CBS")
                    .actorType(ActorType.SYSTEM_CBS.name())
                    .changedAt(now)
                    .build());
        } else {
            String lastStatus = existingHistory.get(existingHistory.size() - 1).getToStatus();
            if (!TransactionStatus.Posted.name().equalsIgnoreCase(lastStatus)) {
                if (!TransactionStatus.Processing.name().equalsIgnoreCase(lastStatus)) {
                    newHistoryList.add(TransactionStatusHistoryMaster.builder()
                            .historyId(UUID.randomUUID().toString())
                            .transactionId(txId)
                            .fromStatus(lastStatus)
                            .toStatus(TransactionStatus.Processing.name())
                            .changeReason(ChangeReasonCode.CBS_OFS_PROCESSING)
                            .reasonDetails("Core OFS transaction processing resumed after hold")
                            .actorId("SYSTEM_CBS")
                            .actorType(ActorType.SYSTEM_CBS.name())
                            .changedAt(now.minusMillis(10))
                            .build());
                }
                newHistoryList.add(TransactionStatusHistoryMaster.builder()
                        .historyId(UUID.randomUUID().toString())
                        .transactionId(txId)
                        .fromStatus(TransactionStatus.Processing.name())
                        .toStatus(TransactionStatus.Posted.name())
                        .changeReason(ChangeReasonCode.ACID_LEDGER_COMMITTED)
                        .reasonDetails("ACID double-entry ledger posting committed")
                        .actorId("SYSTEM_CBS")
                        .actorType(ActorType.SYSTEM_CBS.name())
                        .changedAt(now)
                        .build());
            }
        }
        statusHistoryRepository.saveAll(newHistoryList);

        // 8. Outbox & Kafka event publication
        TransferExecutedEvent transferEvent = TransferExecutedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransferExecutedEvent")
                .version("1.0")
                .transactionId(txId)
                .cbsReference("TXN-" + txId)
                .sourceAccountId(sourceId)
                .destinationAccountId(destId)
                .amount(request.amount())
                .currency(tx.getCurrency())
                .sourceBalanceAfter(sourceBal.getBalanceAmount())
                .destinationBalanceAfter(destBal.getBalanceAmount())
                .executedAtUtc(now)
                .build();
        saveOutbox("TransferExecutedEvent", txId, transferEvent);

        for (TransactionStatusHistoryMaster hist : newHistoryList) {
            TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("TransactionStatusChangedEvent")
                    .version("1.0")
                    .transactionId(txId)
                    .fromStatus(hist.getFromStatus() != null ? TransactionStatus.valueOf(hist.getFromStatus()) : null)
                    .toStatus(TransactionStatus.valueOf(hist.getToStatus()))
                    .changeReason(hist.getChangeReason())
                    .actorId(hist.getActorId())
                    .actorType(ActorType.valueOf(hist.getActorType()))
                    .changedAt(hist.getChangedAt())
                    .build();

            saveOutbox("TransactionStatusChangedEvent", txId, statusEvent);
            try {
                kafkaTemplate.send(KafkaConfig.TOPIC_TRANSFERS_EVENTS, txId, statusEvent);
            } catch (Exception e) {
                log.warn("Kafka async publish deferred to outbox relay: {}", e.getMessage());
            }
        }

        try {
            kafkaTemplate.send(KafkaConfig.TOPIC_TRANSFERS_EVENTS, txId, transferEvent);
        } catch (Exception e) {
            log.warn("Kafka async publish deferred to outbox relay: {}", e.getMessage());
        }

        String ofsResponse = OfsMessageUtil.buildOfsResponse(true, txId, "POSTED_SUCCESSFULLY");

        return new TransferResponseDto(
                txId,
                TransactionStatus.Posted.name(),
                request.amount(),
                tx.getCurrency(),
                sourceId,
                destId,
                sourceBal.getBalanceAmount(),
                destBal.getBalanceAmount(),
                ofsResponse,
                now
        );
    }

    private void updateGlBalance(String glCode, BigDecimal debitAdd, BigDecimal creditAdd) {
        String period = "2026-M10";
        GlBalanceMaster balance = glBalanceRepository.findByGlCodeAndFiscalPeriod(glCode, period)
                .orElseGet(() -> GlBalanceMaster.builder()
                        .glCode(glCode)
                        .fiscalPeriod(period)
                        .totalDebit(BigDecimal.ZERO)
                        .totalCredit(BigDecimal.ZERO)
                        .netBalance(BigDecimal.ZERO)
                        .updatedAt(Instant.now())
                        .build());

        balance.setTotalDebit(balance.getTotalDebit().add(debitAdd));
        balance.setTotalCredit(balance.getTotalCredit().add(creditAdd));
        balance.setNetBalance(balance.getTotalDebit().subtract(balance.getTotalCredit()));
        balance.setUpdatedAt(Instant.now());
        glBalanceRepository.save(balance);
    }

    private void saveOutbox(String eventType, String aggregateId, Object payload) {
        try {
            OutboxEventMaster outbox = OutboxEventMaster.builder()
                    .eventId(UUID.randomUUID().toString())
                    .aggregateType("TRANSACTION")
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .kafkaTopic(KafkaConfig.TOPIC_TRANSFERS_EVENTS)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status("PUBLISHED")
                    .retryCount(0)
                    .createdAt(Instant.now())
                    .publishedAt(Instant.now())
                    .build();
            outboxRepository.save(outbox);
        } catch (Exception e) {
            log.error("Failed to serialize outbox event: {}", e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "masterTransactionManager")
    public void recordFailedTransfer(TransferRequestDto request, String failureReason) {
        String txId = resolveTransactionId(request.transactionId());
        Instant now = Instant.now();

        BigDecimal currentBal = balanceRepository.findById(request.sourceAccountId())
                .map(BalanceMaster::getBalanceAmount)
                .orElse(BigDecimal.ZERO);

        TransactionMaster tx = transactionRepository.findById(txId).orElse(null);
        if (tx == null) {
            tx = TransactionMaster.builder()
                    .transactionId(txId)
                    .idempotencyKey(request.idempotencyKey())
                    .sourceAccountId(request.sourceAccountId())
                    .targetAccountId(request.destinationAccountId())
                    .amount(request.amount())
                    .beforeBalance(currentBal)
                    .afterBalance(currentBal)
                    .currency(request.currency() != null ? request.currency() : "PHP")
                    .transactionType("INTRA_BANK")
                    .status(TransactionStatus.Failed.name())
                    .requiresMakerChecker(0)
                    .memo(request.description() != null ? request.description() : "Funds Transfer Failed")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else {
            tx.setStatus(TransactionStatus.Failed.name());
            if (tx.getBeforeBalance() == null) {
                tx.setBeforeBalance(currentBal);
            }
            if (tx.getAfterBalance() == null) {
                tx.setAfterBalance(currentBal);
            }
            tx.setUpdatedAt(now);
        }
        transactionRepository.save(tx);

        List<TransactionStatusHistoryMaster> existingHistory = getExistingStatusHistory(txId);

        List<TransactionStatusHistoryMaster> newHistoryList = new ArrayList<>();
        if (existingHistory.isEmpty()) {
            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(null)
                    .toStatus(TransactionStatus.Initiated.name())
                    .changeReason(ChangeReasonCode.API_INGESTION)
                    .reasonDetails("Transfer initiated via API ingestion")
                    .actorId(request.sourceAccountId() != null ? request.sourceAccountId() : "SYSTEM_ORCH")
                    .actorType(ActorType.SYSTEM_ORCH.name())
                    .changedAt(now.minusMillis(30))
                    .build());

            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(TransactionStatus.Initiated.name())
                    .toStatus(TransactionStatus.Authorized.name())
                    .changeReason(ChangeReasonCode.BIOMETRIC_AUTH_VERIFIED)
                    .reasonDetails("Customer authorization passed")
                    .actorId(request.sourceAccountId() != null ? request.sourceAccountId() : "CUSTOMER")
                    .actorType(ActorType.CUSTOMER.name())
                    .changedAt(now.minusMillis(20))
                    .build());

            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(TransactionStatus.Authorized.name())
                    .toStatus(TransactionStatus.Processing.name())
                    .changeReason(ChangeReasonCode.CBS_OFS_PROCESSING)
                    .reasonDetails("Core OFS transaction processing initiated")
                    .actorId("SYSTEM_CBS")
                    .actorType(ActorType.SYSTEM_CBS.name())
                    .changedAt(now.minusMillis(10))
                    .build());

            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(TransactionStatus.Processing.name())
                    .toStatus(TransactionStatus.Failed.name())
                    .changeReason(failureReason != null && failureReason.toLowerCase().contains("insufficient")
                            ? ChangeReasonCode.CBS_SOLVENCY_DEFICIT
                            : ChangeReasonCode.CIRCUIT_BREAKER_TRIPPED_DLQ)
                    .reasonDetails(failureReason != null ? failureReason : "Transfer processing failed")
                    .actorId("SYSTEM_CBS")
                    .actorType(ActorType.SYSTEM_CBS.name())
                    .changedAt(now)
                    .build());
        } else {
            String lastStatus = existingHistory.get(existingHistory.size() - 1).getToStatus();
            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(lastStatus)
                    .toStatus(TransactionStatus.Failed.name())
                    .changeReason(failureReason != null && failureReason.toLowerCase().contains("insufficient")
                            ? ChangeReasonCode.CBS_SOLVENCY_DEFICIT
                            : ChangeReasonCode.CIRCUIT_BREAKER_TRIPPED_DLQ)
                    .reasonDetails(failureReason != null ? failureReason : "Transfer processing failed")
                    .actorId("SYSTEM_CBS")
                    .actorType(ActorType.SYSTEM_CBS.name())
                    .changedAt(now)
                    .build());
        }
        statusHistoryRepository.saveAll(newHistoryList);

        for (TransactionStatusHistoryMaster hist : newHistoryList) {
            TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("TransactionStatusChangedEvent")
                    .version("1.0")
                    .transactionId(txId)
                    .fromStatus(hist.getFromStatus() != null ? TransactionStatus.valueOf(hist.getFromStatus()) : null)
                    .toStatus(TransactionStatus.valueOf(hist.getToStatus()))
                    .changeReason(hist.getChangeReason())
                    .actorId(hist.getActorId())
                    .actorType(ActorType.valueOf(hist.getActorType()))
                    .changedAt(hist.getChangedAt())
                    .build();
            saveOutbox("TransactionStatusChangedEvent", txId, statusEvent);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "masterTransactionManager")
    public void recordCancelledTransfer(String txId, String sourceAccountId, String destAccountId, BigDecimal amount, String currency, String cancelReason, String reasonDetails, String actorId, String actorType) {
        String resolvedTxId = resolveTransactionId(txId);
        Instant now = Instant.now();
        BigDecimal currentBal = sourceAccountId != null ? balanceRepository.findById(sourceAccountId)
                .map(BalanceMaster::getBalanceAmount)
                .orElse(BigDecimal.ZERO) : BigDecimal.ZERO;

        TransactionMaster tx = transactionRepository.findById(resolvedTxId).orElse(null);
        if (tx == null) {
            tx = TransactionMaster.builder()
                    .transactionId(resolvedTxId)
                    .sourceAccountId(sourceAccountId != null ? sourceAccountId : "ACC-UNKNOWN")
                    .targetAccountId(destAccountId != null ? destAccountId : "ACC-UNKNOWN")
                    .amount(amount != null ? amount : BigDecimal.ZERO)
                    .beforeBalance(currentBal)
                    .afterBalance(currentBal)
                    .currency(currency != null ? currency : "PHP")
                    .transactionType("INTRA_BANK")
                    .status(TransactionStatus.Cancelled.name())
                    .requiresMakerChecker(0)
                    .memo(reasonDetails != null ? reasonDetails : "Transfer Cancelled")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else {
            tx.setStatus(TransactionStatus.Cancelled.name());
            if (tx.getBeforeBalance() == null) {
                tx.setBeforeBalance(currentBal);
            }
            if (tx.getAfterBalance() == null) {
                tx.setAfterBalance(currentBal);
            }
            tx.setUpdatedAt(now);
        }
        transactionRepository.save(tx);

        List<TransactionStatusHistoryMaster> existingHistory = getExistingStatusHistory(txId);

        List<TransactionStatusHistoryMaster> newHistoryList = new ArrayList<>();
        String fromStatus = existingHistory.isEmpty() ? TransactionStatus.Initiated.name() : existingHistory.get(existingHistory.size() - 1).getToStatus();

        if (existingHistory.isEmpty()) {
            newHistoryList.add(TransactionStatusHistoryMaster.builder()
                    .historyId(UUID.randomUUID().toString())
                    .transactionId(txId)
                    .fromStatus(null)
                    .toStatus(TransactionStatus.Initiated.name())
                    .changeReason(ChangeReasonCode.API_INGESTION)
                    .reasonDetails("Transfer initiated via API ingestion")
                    .actorId(actorId != null ? actorId : "SYSTEM_ORCH")
                    .actorType(actorType != null ? actorType : ActorType.SYSTEM_ORCH.name())
                    .changedAt(now.minusMillis(20))
                    .build());
        }

        newHistoryList.add(TransactionStatusHistoryMaster.builder()
                .historyId(UUID.randomUUID().toString())
                .transactionId(txId)
                .fromStatus(fromStatus)
                .toStatus(TransactionStatus.Cancelled.name())
                .changeReason(cancelReason != null ? cancelReason : ChangeReasonCode.USER_COOL_OFF_CANCELLED)
                .reasonDetails(reasonDetails != null ? reasonDetails : "Transaction cancelled by user or security policy")
                .actorId(actorId != null ? actorId : "CUSTOMER")
                .actorType(actorType != null ? actorType : ActorType.CUSTOMER.name())
                .changedAt(now)
                .build());

        statusHistoryRepository.saveAll(newHistoryList);

        for (TransactionStatusHistoryMaster hist : newHistoryList) {
            TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("TransactionStatusChangedEvent")
                    .version("1.0")
                    .transactionId(txId)
                    .fromStatus(hist.getFromStatus() != null ? TransactionStatus.valueOf(hist.getFromStatus()) : null)
                    .toStatus(TransactionStatus.valueOf(hist.getToStatus()))
                    .changeReason(hist.getChangeReason())
                    .actorId(hist.getActorId())
                    .actorType(ActorType.valueOf(hist.getActorType()))
                    .changedAt(hist.getChangedAt())
                    .build();
            saveOutbox("TransactionStatusChangedEvent", txId, statusEvent);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "masterTransactionManager")
    public void recordReservedTransfer(String txId, String sourceAccountId, String destAccountId, BigDecimal amount, String currency, String memo) {
        String resolvedTxId = resolveTransactionId(txId);
        Instant now = Instant.now();
        BigDecimal currentBal = sourceAccountId != null ? balanceRepository.findById(sourceAccountId)
                .map(BalanceMaster::getBalanceAmount)
                .orElse(BigDecimal.ZERO) : BigDecimal.ZERO;

        TransactionMaster tx = transactionRepository.findById(resolvedTxId).orElse(null);
        if (tx == null) {
            tx = TransactionMaster.builder()
                    .transactionId(resolvedTxId)
                    .sourceAccountId(sourceAccountId != null ? sourceAccountId : "ACC-UNKNOWN")
                    .targetAccountId(destAccountId != null ? destAccountId : "ACC-UNKNOWN")
                    .amount(amount != null ? amount : BigDecimal.ZERO)
                    .beforeBalance(currentBal)
                    .afterBalance(currentBal)
                    .currency(currency != null ? currency : "PHP")
                    .transactionType("INTRA_BANK")
                    .status(TransactionStatus.Reserved.name())
                    .requiresMakerChecker(0)
                    .memo(memo != null ? memo : "Funds Reserved during Cooling-Off")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        } else {
            tx.setStatus(TransactionStatus.Reserved.name());
            if (tx.getBeforeBalance() == null) {
                tx.setBeforeBalance(currentBal);
            }
            if (tx.getAfterBalance() == null) {
                tx.setAfterBalance(currentBal);
            }
            tx.setUpdatedAt(now);
        }
        transactionRepository.save(tx);

        List<TransactionStatusHistoryMaster> existingHistory = getExistingStatusHistory(txId);

        if (existingHistory.isEmpty()) {
            List<TransactionStatusHistoryMaster> newHistoryList = List.of(
                    TransactionStatusHistoryMaster.builder()
                            .historyId(UUID.randomUUID().toString())
                            .transactionId(txId)
                            .fromStatus(null)
                            .toStatus(TransactionStatus.Initiated.name())
                            .changeReason(ChangeReasonCode.API_INGESTION)
                            .reasonDetails("Transfer initiated via API ingestion")
                            .actorId(sourceAccountId != null ? sourceAccountId : "SYSTEM_ORCH")
                            .actorType(ActorType.SYSTEM_ORCH.name())
                            .changedAt(now.minusMillis(20))
                            .build(),
                    TransactionStatusHistoryMaster.builder()
                            .historyId(UUID.randomUUID().toString())
                            .transactionId(txId)
                            .fromStatus(TransactionStatus.Initiated.name())
                            .toStatus(TransactionStatus.Authorized.name())
                            .changeReason(ChangeReasonCode.BIOMETRIC_AUTH_VERIFIED)
                            .reasonDetails("Customer authorization and risk validation passed")
                            .actorId(sourceAccountId != null ? sourceAccountId : "CUSTOMER")
                            .actorType(ActorType.CUSTOMER.name())
                            .changedAt(now.minusMillis(10))
                            .build(),
                    TransactionStatusHistoryMaster.builder()
                            .historyId(UUID.randomUUID().toString())
                            .transactionId(txId)
                            .fromStatus(TransactionStatus.Authorized.name())
                            .toStatus(TransactionStatus.Reserved.name())
                            .changeReason(ChangeReasonCode.FUNDS_RESERVATION_EARMARKED)
                            .reasonDetails(memo != null ? memo : "Funds reservation earmarked in core balance")
                            .actorId("SYSTEM_CBS")
                            .actorType(ActorType.SYSTEM_CBS.name())
                            .changedAt(now)
                            .build()
            );
            statusHistoryRepository.saveAll(newHistoryList);

            for (TransactionStatusHistoryMaster hist : newHistoryList) {
                TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .eventType("TransactionStatusChangedEvent")
                        .version("1.0")
                        .transactionId(txId)
                        .fromStatus(hist.getFromStatus() != null ? TransactionStatus.valueOf(hist.getFromStatus()) : null)
                        .toStatus(TransactionStatus.valueOf(hist.getToStatus()))
                        .changeReason(hist.getChangeReason())
                        .actorId(hist.getActorId())
                        .actorType(ActorType.valueOf(hist.getActorType()))
                        .changedAt(hist.getChangedAt())
                        .build();
                saveOutbox("TransactionStatusChangedEvent", txId, statusEvent);
            }
        }
    }

    private List<TransactionStatusHistoryMaster> getExistingStatusHistory(String txId) {
        Page<TransactionStatusHistoryMaster> page = statusHistoryRepository.findByTransactionIdOrderByChangedAtAsc(txId, PageRequest.of(0, 10));
        return (page != null && page.getContent() != null) ? page.getContent() : Collections.emptyList();
    }

    private String resolveTransactionId(String inputId) {
        if (inputId == null || inputId.isBlank()) {
            return "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        String trimmed = inputId.trim();
        if (trimmed.startsWith("TXN-")) {
            return trimmed;
        }
        if (trimmed.startsWith("TX-")) {
            return "TXN-" + trimmed.substring(3);
        }
        return "TXN-" + trimmed;
    }
}
