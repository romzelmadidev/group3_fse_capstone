package com.bank.cbs.service;

import com.bank.cbs.config.KafkaConfig;
import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.entity.master.*;
import com.bank.cbs.repository.master.*;
import com.bank.ledger.contracts.dto.T24ReversalRequest;
import com.bank.ledger.contracts.dto.T24ReversalResponse;
import com.bank.ledger.contracts.dto.events.TransactionStatusChangedEvent;
import com.bank.ledger.contracts.dto.events.TransferReversedEvent;
import com.bank.ledger.contracts.enums.ActorType;
import com.bank.ledger.contracts.enums.ChangeReasonCode;
import com.bank.ledger.contracts.enums.TransactionStatus;
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
public class CbsReversalService {

    private static final Logger log = LoggerFactory.getLogger(CbsReversalService.class);

    private final ReversalRequestMasterRepository reversalRequestRepository;
    private final TransactionMasterRepository transactionRepository;
    private final BalanceMasterRepository balanceRepository;
    private final GlLedgerMasterRepository glLedgerRepository;
    private final TransactionStatusHistoryMasterRepository statusHistoryRepository;
    private final OutboxEventMasterRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public CbsReversalService(
            ReversalRequestMasterRepository reversalRequestRepository,
            TransactionMasterRepository transactionRepository,
            BalanceMasterRepository balanceRepository,
            GlLedgerMasterRepository glLedgerRepository,
            TransactionStatusHistoryMasterRepository statusHistoryRepository,
            OutboxEventMasterRepository outboxRepository,
            KafkaTemplate<String, Object> kafkaTemplate,
            ObjectMapper objectMapper) {
        this.reversalRequestRepository = reversalRequestRepository;
        this.transactionRepository = transactionRepository;
        this.balanceRepository = balanceRepository;
        this.glLedgerRepository = glLedgerRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional("masterTransactionManager")
    public ReversalRequestMaster requestReversal(ReversalRequestDto dto) {
        TransactionMaster originalTx = transactionRepository.findById(dto.originalTransactionId())
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + dto.originalTransactionId()));

        if (!TransactionStatus.Posted.name().equalsIgnoreCase(originalTx.getStatus())) {
            throw new IllegalStateException("Only POSTED transactions can be reversed. Current status: " + originalTx.getStatus());
        }

        originalTx.setStatus(TransactionStatus.PendingReversal.name());
        originalTx.setUpdatedAt(Instant.now());
        transactionRepository.save(originalTx);

        String ticketId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        ReversalRequestMaster reversal = ReversalRequestMaster.builder()
                .ticketId(ticketId)
                .originalTxId(originalTx.getTransactionId())
                .makerId(dto.makerId())
                .disputeReason(dto.reason() != null ? dto.reason() : "CUSTOMER_DISPUTE")
                .makerNotes(dto.notes() != null ? dto.notes() : "Reversal requested by maker")
                .status("PENDING")
                .createdAt(now)
                .build();
        reversal = reversalRequestRepository.save(reversal);

        TransactionStatusHistoryMaster statusHistory = TransactionStatusHistoryMaster.builder()
                .historyId(UUID.randomUUID().toString())
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.Posted.name())
                .toStatus(TransactionStatus.PendingReversal.name())
                .changeReason(ChangeReasonCode.MAKER_DISPUTE_FILED)
                .reasonDetails("Reversal initiated by maker: " + dto.makerId())
                .actorId(dto.makerId())
                .actorType(ActorType.TELLER_MAKER.name())
                .changedAt(now)
                .build();
        statusHistoryRepository.save(statusHistory);

        TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionStatusChangedEvent")
                .version("1.0")
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.Posted)
                .toStatus(TransactionStatus.PendingReversal)
                .changeReason(ChangeReasonCode.MAKER_DISPUTE_FILED)
                .actorId(dto.makerId())
                .actorType(ActorType.TELLER_MAKER)
                .changedAt(now)
                .build();
        publishEvent("TransactionStatusChangedEvent", originalTx.getTransactionId(), statusEvent);

        return reversal;
    }

    @Transactional("masterTransactionManager")
    public ReversalRequestMaster approveReversal(ReversalActionDto action) {
        ReversalRequestMaster request = reversalRequestRepository.findById(action.reversalRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Reversal request not found: " + action.reversalRequestId()));

        if (!"PENDING".equalsIgnoreCase(request.getStatus())) {
            throw new IllegalStateException("Reversal request is not in PENDING state: " + request.getStatus());
        }

        // Strict dual-control check: Checker CANNOT be Maker
        if (action.checkerId().equalsIgnoreCase(request.getMakerId())) {
            throw new IllegalArgumentException("Dual control violation: Checker cannot be the same person as Maker (" + action.checkerId() + ")");
        }

        TransactionMaster originalTx = transactionRepository.findById(request.getOriginalTxId())
                .orElseThrow(() -> new IllegalArgumentException("Original transaction not found"));

        String sourceId = originalTx.getSourceAccountId(); // Original sender (will be refunded)
        String destId = originalTx.getTargetAccountId(); // Original beneficiary (will be debited back)

        String firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
        String secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

        BalanceMaster firstBal = balanceRepository.findByAccountIdForUpdate(firstLockId)
                .orElseThrow(() -> new IllegalArgumentException("Balance not found for ID: " + firstLockId));
        BalanceMaster secondBal = balanceRepository.findByAccountIdForUpdate(secondLockId)
                .orElseThrow(() -> new IllegalArgumentException("Balance not found for ID: " + secondLockId));

        BalanceMaster senderBal = sourceId.equals(firstLockId) ? firstBal : secondBal;
        BalanceMaster beneficiaryBal = destId.equals(firstLockId) ? firstBal : secondBal;

        // Check if beneficiary has enough available funds to reverse
        BigDecimal beneficiaryAvailable = beneficiaryBal.getAvailableBalance();
        if (beneficiaryAvailable.compareTo(originalTx.getAmount()) < 0) {
            throw new IllegalStateException("Beneficiary account has insufficient funds to process reversal: available="
                    + beneficiaryAvailable + ", required=" + originalTx.getAmount());
        }

        Instant now = Instant.now();

        // Reverse balances
        beneficiaryBal.setBalanceAmount(beneficiaryBal.getBalanceAmount().subtract(originalTx.getAmount()));
        BigDecimal benHold = beneficiaryBal.getHoldAmount() != null ? beneficiaryBal.getHoldAmount() : BigDecimal.ZERO;
        beneficiaryBal.setAvailableBalance(beneficiaryBal.getBalanceAmount().subtract(benHold));
        beneficiaryBal.setUpdatedAt(now);
        balanceRepository.save(beneficiaryBal);

        senderBal.setBalanceAmount(senderBal.getBalanceAmount().add(originalTx.getAmount()));
        BigDecimal sendHold = senderBal.getHoldAmount() != null ? senderBal.getHoldAmount() : BigDecimal.ZERO;
        senderBal.setAvailableBalance(senderBal.getBalanceAmount().subtract(sendHold));
        senderBal.setUpdatedAt(now);
        balanceRepository.save(senderBal);

        // Compensating GL entries
        String reversalTxId = UUID.randomUUID().toString();
        LocalDate valDate = LocalDate.now();

        GlLedgerMaster compensatingDr = GlLedgerMaster.builder()
                .journalId(UUID.randomUUID().toString())
                .transactionId(reversalTxId)
                .glCode("20100")
                .debitAmount(originalTx.getAmount())
                .creditAmount(BigDecimal.ZERO)
                .postingDate(valDate)
                .createdAt(now)
                .build();
        glLedgerRepository.save(compensatingDr);

        GlLedgerMaster compensatingCr = GlLedgerMaster.builder()
                .journalId(UUID.randomUUID().toString())
                .transactionId(reversalTxId)
                .glCode("20100")
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(originalTx.getAmount())
                .postingDate(valDate)
                .createdAt(now)
                .build();
        glLedgerRepository.save(compensatingCr);

        // Update reversal record
        request.setCheckerId(action.checkerId());
        request.setCheckerNotes(action.checkerNotes() != null ? action.checkerNotes() : "Approved by checker");
        request.setStatus("APPROVED");
        request.setResolvedAt(now);
        request.setReversalTxId(reversalTxId);
        request = reversalRequestRepository.save(request);

        // Update original transaction
        originalTx.setStatus(TransactionStatus.Reversed.name());
        originalTx.setUpdatedAt(now);
        transactionRepository.save(originalTx);

        // Create reversal transaction record
        TransactionMaster reversalTx = TransactionMaster.builder()
                .transactionId(reversalTxId)
                .sourceAccountId(destId)
                .targetAccountId(sourceId)
                .amount(originalTx.getAmount())
                .currency(originalTx.getCurrency())
                .transactionType("REVERSAL")
                .status(TransactionStatus.Reversed.name())
                .requiresMakerChecker(1)
                .approvedBy(action.checkerId())
                .memo("Reversal of transaction " + originalTx.getTransactionId() + ": " + request.getDisputeReason())
                .createdAt(now)
                .updatedAt(now)
                .build();
        transactionRepository.save(reversalTx);

        // Status history
        TransactionStatusHistoryMaster statusHistory = TransactionStatusHistoryMaster.builder()
                .historyId(UUID.randomUUID().toString())
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.PendingReversal.name())
                .toStatus(TransactionStatus.Reversed.name())
                .changeReason(ChangeReasonCode.CHECKER_REVERSAL_APPROVED_SETTLED)
                .reasonDetails("Reversal approved by checker: " + action.checkerId())
                .actorId(action.checkerId())
                .actorType(ActorType.MANAGER_CHECKER.name())
                .changedAt(now)
                .build();
        statusHistoryRepository.save(statusHistory);

        // Events
        TransferReversedEvent reversedEvent = TransferReversedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransferReversedEvent")
                .version("1.0")
                .ticketId(request.getTicketId())
                .originalTransactionId(originalTx.getTransactionId())
                .reversalTransactionId(reversalTxId)
                .cbsReference("REV-" + reversalTxId)
                .beneficiaryAccountId(destId)
                .originalSenderAccountId(sourceId)
                .amount(originalTx.getAmount())
                .currency(originalTx.getCurrency())
                .makerId(request.getMakerId())
                .checkerId(action.checkerId())
                .beneficiaryBalanceAfter(beneficiaryBal.getBalanceAmount())
                .originalSenderBalanceAfter(senderBal.getBalanceAmount())
                .executedAtUtc(now)
                .build();

        TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionStatusChangedEvent")
                .version("1.0")
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.PendingReversal)
                .toStatus(TransactionStatus.Reversed)
                .changeReason(ChangeReasonCode.CHECKER_REVERSAL_APPROVED_SETTLED)
                .actorId(action.checkerId())
                .actorType(ActorType.MANAGER_CHECKER)
                .changedAt(now)
                .build();

        publishEvent("TransferReversedEvent", originalTx.getTransactionId(), reversedEvent);
        publishEvent("TransactionStatusChangedEvent", originalTx.getTransactionId(), statusEvent);

        return request;
    }

    @Transactional("masterTransactionManager")
    public ReversalRequestMaster rejectReversal(ReversalActionDto action) {
        ReversalRequestMaster request = reversalRequestRepository.findById(action.reversalRequestId())
                .orElseThrow(() -> new IllegalArgumentException("Reversal request not found: " + action.reversalRequestId()));

        if (!"PENDING".equalsIgnoreCase(request.getStatus())) {
            throw new IllegalStateException("Reversal request is not in PENDING state");
        }

        if (action.checkerId().equalsIgnoreCase(request.getMakerId())) {
            throw new IllegalArgumentException("Dual control violation: Checker cannot be Maker");
        }

        Instant now = Instant.now();
        request.setCheckerId(action.checkerId());
        request.setStatus("REJECTED");
        request.setCheckerNotes(action.rejectionReason());
        request.setResolvedAt(now);
        request = reversalRequestRepository.save(request);

        TransactionMaster originalTx = transactionRepository.findById(request.getOriginalTxId())
                .orElseThrow(() -> new IllegalArgumentException("Original transaction not found"));

        originalTx.setStatus(TransactionStatus.Posted.name());
        originalTx.setUpdatedAt(now);
        transactionRepository.save(originalTx);

        TransactionStatusHistoryMaster statusHistory = TransactionStatusHistoryMaster.builder()
                .historyId(UUID.randomUUID().toString())
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.PendingReversal.name())
                .toStatus(TransactionStatus.Posted.name())
                .changeReason(ChangeReasonCode.CHECKER_REVERSAL_REJECTED)
                .reasonDetails("Reversal rejected by checker: " + action.checkerId() + ". Reason: " + action.rejectionReason())
                .actorId(action.checkerId())
                .actorType(ActorType.MANAGER_CHECKER.name())
                .changedAt(now)
                .build();
        statusHistoryRepository.save(statusHistory);

        TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionStatusChangedEvent")
                .version("1.0")
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.PendingReversal)
                .toStatus(TransactionStatus.Posted)
                .changeReason(ChangeReasonCode.CHECKER_REVERSAL_REJECTED)
                .actorId(action.checkerId())
                .actorType(ActorType.MANAGER_CHECKER)
                .changedAt(now)
                .build();
        publishEvent("TransactionStatusChangedEvent", originalTx.getTransactionId(), statusEvent);

        return request;
    }

    @Transactional("masterTransactionManager")
    public T24ReversalResponse executeCompensatingReversal(T24ReversalRequest req) {
        String origTxId = req.getOriginalTransactionId();
        if (origTxId == null || origTxId.isBlank()) {
            throw new IllegalArgumentException("original_transaction_id is required for reversal");
        }

        TransactionMaster originalTx = transactionRepository.findById(origTxId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + origTxId));

        if (!TransactionStatus.Posted.name().equalsIgnoreCase(originalTx.getStatus())) {
            throw new IllegalStateException("Only POSTED transactions can be reversed. Current status: " + originalTx.getStatus());
        }

        String sourceId = originalTx.getSourceAccountId(); // Original sender (will be refunded)
        String destId = originalTx.getTargetAccountId(); // Original beneficiary (will be debited back)

        String firstLockId = sourceId.compareTo(destId) < 0 ? sourceId : destId;
        String secondLockId = sourceId.compareTo(destId) < 0 ? destId : sourceId;

        BalanceMaster firstBal = balanceRepository.findByAccountIdForUpdate(firstLockId)
                .orElseThrow(() -> new IllegalArgumentException("Balance not found for ID: " + firstLockId));
        BalanceMaster secondBal = balanceRepository.findByAccountIdForUpdate(secondLockId)
                .orElseThrow(() -> new IllegalArgumentException("Balance not found for ID: " + secondLockId));

        BalanceMaster senderBal = sourceId.equals(firstLockId) ? firstBal : secondBal;
        BalanceMaster beneficiaryBal = destId.equals(firstLockId) ? firstBal : secondBal;

        BigDecimal beneficiaryAvailable = beneficiaryBal.getAvailableBalance();
        if (beneficiaryAvailable.compareTo(originalTx.getAmount()) < 0) {
            throw new IllegalStateException("Beneficiary account has insufficient funds to process reversal: available="
                    + beneficiaryAvailable + ", required=" + originalTx.getAmount());
        }

        Instant now = Instant.now();

        // Reverse balances
        beneficiaryBal.setBalanceAmount(beneficiaryBal.getBalanceAmount().subtract(originalTx.getAmount()));
        BigDecimal benHold = beneficiaryBal.getHoldAmount() != null ? beneficiaryBal.getHoldAmount() : BigDecimal.ZERO;
        beneficiaryBal.setAvailableBalance(beneficiaryBal.getBalanceAmount().subtract(benHold));
        beneficiaryBal.setUpdatedAt(now);
        balanceRepository.save(beneficiaryBal);

        senderBal.setBalanceAmount(senderBal.getBalanceAmount().add(originalTx.getAmount()));
        BigDecimal sendHold = senderBal.getHoldAmount() != null ? senderBal.getHoldAmount() : BigDecimal.ZERO;
        senderBal.setAvailableBalance(senderBal.getBalanceAmount().subtract(sendHold));
        senderBal.setUpdatedAt(now);
        balanceRepository.save(senderBal);

        // Compensating GL entries
        String reversalTxId = UUID.randomUUID().toString();
        LocalDate valDate = LocalDate.now();

        GlLedgerMaster compensatingDr = GlLedgerMaster.builder()
                .journalId(UUID.randomUUID().toString())
                .transactionId(reversalTxId)
                .glCode("20100")
                .debitAmount(originalTx.getAmount())
                .creditAmount(BigDecimal.ZERO)
                .postingDate(valDate)
                .createdAt(now)
                .build();
        glLedgerRepository.save(compensatingDr);

        GlLedgerMaster compensatingCr = GlLedgerMaster.builder()
                .journalId(UUID.randomUUID().toString())
                .transactionId(reversalTxId)
                .glCode("20100")
                .debitAmount(BigDecimal.ZERO)
                .creditAmount(originalTx.getAmount())
                .postingDate(valDate)
                .createdAt(now)
                .build();
        glLedgerRepository.save(compensatingCr);

        // Update original transaction
        originalTx.setStatus(TransactionStatus.Reversed.name());
        originalTx.setUpdatedAt(now);
        transactionRepository.save(originalTx);

        // Create reversal transaction record
        String actor = req.getCheckerId() != null ? req.getCheckerId() : (req.getMakerId() != null ? req.getMakerId() : "SAGA_COMPENSATOR");
        TransactionMaster reversalTx = TransactionMaster.builder()
                .transactionId(reversalTxId)
                .sourceAccountId(destId)
                .targetAccountId(sourceId)
                .amount(originalTx.getAmount())
                .currency(originalTx.getCurrency())
                .transactionType("REVERSAL")
                .status(TransactionStatus.Reversed.name())
                .requiresMakerChecker(0)
                .approvedBy(actor)
                .memo("Compensating reversal of transaction " + originalTx.getTransactionId() + ": " + req.getReversalReason())
                .createdAt(now)
                .updatedAt(now)
                .build();
        transactionRepository.save(reversalTx);

        // Status history
        TransactionStatusHistoryMaster statusHistory = TransactionStatusHistoryMaster.builder()
                .historyId(UUID.randomUUID().toString())
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.Posted.name())
                .toStatus(TransactionStatus.Reversed.name())
                .changeReason(ChangeReasonCode.CHECKER_REVERSAL_APPROVED_SETTLED)
                .reasonDetails("Compensating reversal executed: " + req.getReversalReason())
                .actorId(actor)
                .actorType(ActorType.SYSTEM_ORCH.name())
                .changedAt(now)
                .build();
        statusHistoryRepository.save(statusHistory);

        // Events
        TransferReversedEvent reversedEvent = TransferReversedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransferReversedEvent")
                .version("1.0")
                .ticketId("SAGA-" + reversalTxId)
                .originalTransactionId(originalTx.getTransactionId())
                .reversalTransactionId(reversalTxId)
                .cbsReference("REV-" + reversalTxId)
                .beneficiaryAccountId(destId)
                .originalSenderAccountId(sourceId)
                .amount(originalTx.getAmount())
                .currency(originalTx.getCurrency())
                .makerId(req.getMakerId() != null ? req.getMakerId() : "SAGA_COORDINATOR")
                .checkerId(actor)
                .beneficiaryBalanceAfter(beneficiaryBal.getBalanceAmount())
                .originalSenderBalanceAfter(senderBal.getBalanceAmount())
                .executedAtUtc(now)
                .build();

        TransactionStatusChangedEvent statusEvent = TransactionStatusChangedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("TransactionStatusChangedEvent")
                .version("1.0")
                .transactionId(originalTx.getTransactionId())
                .fromStatus(TransactionStatus.Posted)
                .toStatus(TransactionStatus.Reversed)
                .changeReason(ChangeReasonCode.CHECKER_REVERSAL_APPROVED_SETTLED)
                .actorId(actor)
                .actorType(ActorType.SYSTEM_ORCH)
                .changedAt(now)
                .build();

        publishEvent("TransferReversedEvent", originalTx.getTransactionId(), reversedEvent);
        publishEvent("TransactionStatusChangedEvent", originalTx.getTransactionId(), statusEvent);

        String ofsResponse = String.format("FUNDS.TRANSFER//1,REVERSED,TRANSACTION.ID:1:1=%s,REVERSAL.ID:1:1=REV-%s,ORIGINAL.TX:1:1=%s",
                reversalTxId, reversalTxId, origTxId);

        return T24ReversalResponse.builder()
                .reversalReference("REV-" + reversalTxId)
                .originalTransactionId(origTxId)
                .status("REVERSED")
                .reversalReason(req.getReversalReason())
                .amount(originalTx.getAmount())
                .debitedAccountId(destId)
                .debitedBalanceAfter(beneficiaryBal.getBalanceAmount())
                .creditedAccountId(sourceId)
                .creditedBalanceAfter(senderBal.getBalanceAmount())
                .ofsResponse(ofsResponse)
                .message("Transaction reversed successfully via T24 compensating workflow")
                .timestamp(now)
                .build();
    }

    private void publishEvent(String eventType, String aggregateId, Object payload) {
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

            kafkaTemplate.send(KafkaConfig.TOPIC_TRANSFERS_EVENTS, aggregateId, payload);
        } catch (Exception e) {
            log.error("Failed to publish reversal event {}: {}", eventType, e.getMessage());
        }
    }
}
