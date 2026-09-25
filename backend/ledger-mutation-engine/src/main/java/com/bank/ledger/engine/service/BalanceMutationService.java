package com.bank.ledger.engine.service;

import com.bank.ledger.contracts.dto.MutationRequest;
import com.bank.ledger.contracts.dto.MutationResponse;
import com.bank.ledger.contracts.exception.InsufficientFundsException;
import com.bank.ledger.engine.dto.event.NotificationAlertEvent;
import com.bank.ledger.engine.dto.event.TransactionEvent;
import com.bank.ledger.engine.entity.audit.LedgerMutationAudit;
import com.bank.ledger.engine.entity.master.BalanceMaster;
import com.bank.ledger.engine.kafka.KafkaEventPublisher;
import com.bank.ledger.engine.repository.audit.LedgerMutationAuditRepository;
import com.bank.ledger.engine.repository.master.BalanceMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceMutationService {

    private final BalanceMasterRepository balanceRepository;
    private final LedgerMutationAuditRepository auditRepository;
    private final KafkaEventPublisher kafkaPublisher;

    /**
     * Executes atomic fund transfer with:
     * 1. Deterministic Lock Ordering (Deadlock-Free Pessimistic Write Lock)
     * 2. Available Balance Validation (Negative Balance Prevention)
     * 3. Oracle Master Balance Mutation
     * 4. PostgreSQL Immutable Dual-Write Audit
     * 5. Asynchronous Kafka Event Streaming (Transaction & Notifications)
     */
    @Transactional(transactionManager = "oracleTransactionManager")
    public MutationResponse executeTransfer(MutationRequest request) {
        String sourceId = request.getAccountId();
        String targetId = request.getTargetAccountId();
        BigDecimal amount = request.getMutationAmount();

        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("Source and target accounts must be different.");
        }

        log.info("[MUTATION START] TxId: {}, Transfer PHP {} from {} to {}",
                request.getTransactionId(), amount, sourceId, targetId);

        // =========================================================================
        // STEP 1: DEADLOCK PREVENTION VIA DETERMINISTIC LOCK ORDERING
        // =========================================================================
        // Always acquire locks in alphabetical order: Prevents A->B vs B->A deadlocks!
        String firstLockId = sourceId.compareTo(targetId) < 0 ? sourceId : targetId;
        String secondLockId = sourceId.compareTo(targetId) < 0 ? targetId : sourceId;

        BalanceMaster firstAccount = balanceRepository.findByAccountIdWithLock(firstLockId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstLockId));
        BalanceMaster secondAccount = balanceRepository.findByAccountIdWithLock(secondLockId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondLockId));

        BalanceMaster sender = sourceId.equals(firstLockId) ? firstAccount : secondAccount;
        BalanceMaster receiver = targetId.equals(firstLockId) ? firstAccount : secondAccount;

        // =========================================================================
        // STEP 2: MATHEMATICAL FINANCIAL SANITY CHECK
        // =========================================================================
        if (sender.getAvailableBalance().compareTo(amount) < 0) {
            log.error("[MUTATION REJECTED] Insufficient funds: Account {} available PHP {}, requested PHP {}",
                    sourceId, sender.getAvailableBalance(), amount);
            throw new InsufficientFundsException(
                    String.format("Insufficient funds in account %s. Available: PHP %s, Requested: PHP %s",
                            sourceId, sender.getAvailableBalance(), amount));
        }

        BigDecimal senderBefore = sender.getBalanceAmount();
        BigDecimal senderAfter = senderBefore.subtract(amount);

        BigDecimal receiverBefore = receiver.getBalanceAmount();
        BigDecimal receiverAfter = receiverBefore.add(amount);

        // =========================================================================
        // STEP 3: ORACLE MASTER BALANCE MUTATION
        // =========================================================================
        sender.setBalanceAmount(senderAfter);
        sender.setAvailableBalance(sender.getAvailableBalance().subtract(amount));
        sender.setUpdatedAt(Instant.now());

        receiver.setBalanceAmount(receiverAfter);
        receiver.setAvailableBalance(receiver.getAvailableBalance().add(amount));
        receiver.setUpdatedAt(Instant.now());

        balanceRepository.save(sender);
        balanceRepository.save(receiver);
        log.info("[ORACLE MUTATED] Sender new balance: PHP {}, Receiver new balance: PHP {}",
                senderAfter, receiverAfter);

        // =========================================================================
        // STEP 4: POSTGRESQL IMMUTABLE DUAL-WRITE AUDIT TRAIL
        // =========================================================================
        // Leg 1: Sender Debit Audit
        LedgerMutationAudit senderAudit = LedgerMutationAudit.builder()
                .transactionId(request.getTransactionId() + "-DR")
                .accountId(sourceId)
                .mutationType("TRANSFER")
                .mutationAmount(amount)
                .beforeBalance(senderBefore)
                .afterBalance(senderAfter)
                .initiatorUserId(request.getInitiatorUserId())
                .approvedByUserId(request.getApprovedByUserId())
                .status("COMMITTED")
                .createdAt(Instant.now())
                .build();
        auditRepository.save(senderAudit);

        // =========================================================================
        // STEP 5: ASYNCHRONOUS KAFKA STREAMING (Transaction Event & Notifications)
        // =========================================================================
        // 5.1 Transaction Event Bus
        TransactionEvent txEvent = TransactionEvent.builder()
                .transactionId(request.getTransactionId())
                .sourceAccountId(sourceId)
                .destinationAccountId(targetId)
                .amount(amount)
                .currency("PHP")
                .mutationType("TRANSFER")
                .status("COMMITTED")
                .initiatorUserId(request.getInitiatorUserId())
                .timestamp(Instant.now())
                .build();
        kafkaPublisher.publishTransactionEvent(txEvent);

        // 5.2 Customer Notification Alerts (Asynchronous push to Kafka)
        NotificationAlertEvent senderAlert = NotificationAlertEvent.builder()
                .alertId("ALT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .transactionId(request.getTransactionId())
                .recipientUserId(request.getInitiatorUserId())
                .recipientAccountId(sourceId)
                .alertType("TRANSFER_DEBIT")
                .amount(amount)
                .balanceAfter(senderAfter)
                .title("Debit Alert: Funds Transferred")
                .message(String.format("PHP %s has been transferred to %s. New balance: PHP %s",
                        amount, targetId, senderAfter))
                .createdAt(Instant.now())
                .build();
        kafkaPublisher.publishNotificationAlert(senderAlert);

        // =========================================================================
        // STEP 6: BUILD & RETURN FINAL API CONTRACT RESPONSE
        // =========================================================================
        return MutationResponse.builder()
                .transactionId(request.getTransactionId())
                .accountId(sourceId)
                .status("COMMITTED")
                .mutationAmount(amount)
                .balanceBefore(senderBefore)
                .balanceAfter(senderAfter)
                .availableBalance(sender.getAvailableBalance())
                .timestamp(Instant.now())
                .traceId(UUID.randomUUID().toString())
                .build();
    }
}