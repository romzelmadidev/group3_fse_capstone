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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

        destBal.setBalanceAmount(destBal.getBalanceAmount().add(request.amount()));
        BigDecimal destHold = destBal.getHoldAmount() != null ? destBal.getHoldAmount() : BigDecimal.ZERO;
        destBal.setAvailableBalance(destBal.getBalanceAmount().subtract(destHold));
        destBal.setUpdatedAt(now);
        balanceRepository.save(destBal);

        // 5. Double-entry GL postings
        String txId = request.transactionId() != null ? request.transactionId() : UUID.randomUUID().toString();
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
        TransactionMaster tx = TransactionMaster.builder()
                .transactionId(txId)
                .idempotencyKey(request.idempotencyKey())
                .sourceAccountId(sourceId)
                .targetAccountId(destId)
                .amount(request.amount())
                .currency(request.currency() != null ? request.currency() : "PHP")
                .transactionType("INTRA_BANK")
                .status(TransactionStatus.Posted.name())
                .requiresMakerChecker(0)
                .memo(request.description() != null ? request.description() : "Funds Transfer")
                .createdAt(now)
                .updatedAt(now)
                .build();
        transactionRepository.save(tx);

        // 7. Status history record
        TransactionStatusHistoryMaster statusHistory = TransactionStatusHistoryMaster.builder()
                .historyId(UUID.randomUUID().toString())
                .transactionId(txId)
                .fromStatus(null)
                .toStatus(TransactionStatus.Posted.name())
                .changeReason(ChangeReasonCode.ACID_LEDGER_COMMITTED)
                .reasonDetails("Posted via CBS core engine")
                .actorId("SYSTEM")
                .actorType(ActorType.SYSTEM_CBS.name())
                .changedAt(now)
                .build();
        statusHistoryRepository.save(statusHistory);

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

        TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionStatusChangedEvent")
                .version("1.0")
                .transactionId(txId)
                .fromStatus(null)
                .toStatus(TransactionStatus.Posted)
                .changeReason(ChangeReasonCode.ACID_LEDGER_COMMITTED)
                .actorId("SYSTEM")
                .actorType(ActorType.SYSTEM_CBS)
                .changedAt(now)
                .build();

        saveOutbox("TransferExecutedEvent", txId, transferEvent);
        saveOutbox("TransactionStatusChangedEvent", txId, statusEvent);

        try {
            kafkaTemplate.send(KafkaConfig.TOPIC_TRANSFERS_EVENTS, txId, transferEvent);
            kafkaTemplate.send(KafkaConfig.TOPIC_TRANSFERS_EVENTS, txId, statusEvent);
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
}
