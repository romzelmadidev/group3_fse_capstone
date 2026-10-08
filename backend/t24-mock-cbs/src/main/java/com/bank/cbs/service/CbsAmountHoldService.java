package com.bank.cbs.service;

import com.bank.cbs.entity.master.*;
import com.bank.cbs.repository.master.*;
import com.bank.ledger.contracts.dto.AmountHoldCaptureRequestDto;
import com.bank.ledger.contracts.dto.AmountHoldCaptureResponseDto;
import com.bank.ledger.contracts.dto.AmountHoldReleaseResponseDto;
import com.bank.ledger.contracts.dto.AmountHoldRequestDto;
import com.bank.ledger.contracts.dto.AmountHoldResponseDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class CbsAmountHoldService {

    private static final Logger log = LoggerFactory.getLogger(CbsAmountHoldService.class);

    private final AmountHoldMasterRepository holdRepository;
    private final BalanceMasterRepository balanceRepository;
    private final TransactionMasterRepository transactionRepository;
    private final TransactionStatusHistoryMasterRepository statusHistoryRepository;
    private final GlLedgerMasterRepository glLedgerRepository;

    public CbsAmountHoldService(
            AmountHoldMasterRepository holdRepository,
            BalanceMasterRepository balanceRepository) {
        this(holdRepository, balanceRepository, null, null, null);
    }

    @Autowired
    public CbsAmountHoldService(
            AmountHoldMasterRepository holdRepository,
            BalanceMasterRepository balanceRepository,
            @Autowired(required = false) TransactionMasterRepository transactionRepository,
            @Autowired(required = false) TransactionStatusHistoryMasterRepository statusHistoryRepository,
            @Autowired(required = false) GlLedgerMasterRepository glLedgerRepository) {
        this.holdRepository = holdRepository;
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.glLedgerRepository = glLedgerRepository;
    }

    @Transactional("masterTransactionManager")
    public AmountHoldResponseDto createHold(AmountHoldRequestDto request) {
        log.info("Creating amount hold on account {}: amount={}, reason={}, txId={}",
                request.getAccountId(), request.getHoldAmount(), request.getReason(), request.getTransactionId());

        if (request.getHoldAmount() == null || request.getHoldAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Hold amount must be strictly positive.");
        }

        // 1. Acquire pessimistic lock on account balance row
        BalanceMaster balance = balanceRepository.findByAccountIdForUpdate(request.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + request.getAccountId()));

        BigDecimal curBalance = balance.getBalanceAmount();
        BigDecimal curHold = balance.getHoldAmount() != null ? balance.getHoldAmount() : BigDecimal.ZERO;
        BigDecimal curAvailable = curBalance.subtract(curHold);

        // 2. Solvency check against spendable balance
        if (curAvailable.compareTo(request.getHoldAmount()) < 0) {
            throw new IllegalArgumentException(String.format(
                    "Insufficient available balance to place hold. Account %s has available %s, requested hold %s",
                    request.getAccountId(), curAvailable, request.getHoldAmount()));
        }

        // 3. Mutate hold amount and available balance
        BigDecimal newHold = curHold.add(request.getHoldAmount());
        BigDecimal newAvailable = curBalance.subtract(newHold);
        Instant now = Instant.now();

        balance.setHoldAmount(newHold);
        balance.setAvailableBalance(newAvailable);
        balance.setUpdatedAt(now);
        balanceRepository.save(balance);

        // 4. Generate identifiers
        String holdId = "HLD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String lockRef = "ACLK" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        int expiryHours = request.getExpiryHours() != null ? request.getExpiryHours() : 24;
        Instant expiresAt = now.plus(expiryHours, ChronoUnit.HOURS);

        // Link transaction & beneficiary
        String txId = request.getTransactionId() != null && !request.getTransactionId().isBlank()
                ? request.getTransactionId()
                : (request.getExternalReference() != null && !request.getExternalReference().isBlank()
                    ? request.getExternalReference()
                    : "TX-RES-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        String extRef = txId;
        if (request.getTargetAccountId() != null && !request.getTargetAccountId().isBlank()) {
            extRef = txId + "|" + request.getTargetAccountId();
        }

        AmountHoldMaster holdRecord = AmountHoldMaster.builder()
                .holdId(holdId)
                .accountId(request.getAccountId())
                .holdAmount(request.getHoldAmount())
                .reason(request.getReason() != null ? request.getReason() : "MAKER_CHECKER_HOLD")
                .status("ACTIVE")
                .t24LockReference(lockRef)
                .externalReference(extRef)
                .expiresAt(expiresAt)
                .createdAt(now)
                .build();
        holdRepository.save(holdRecord);

        // 5. If transaction repo exists, persist provisional transaction in RESERVED state
        if (transactionRepository != null) {
            try {
                TransactionMaster resTx = TransactionMaster.builder()
                        .transactionId(txId)
                        .sourceAccountId(request.getAccountId())
                        .targetAccountId(request.getTargetAccountId() != null ? request.getTargetAccountId() : request.getAccountId())
                        .amount(request.getHoldAmount())
                        .currency("PHP")
                        .status("RESERVED")
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                transactionRepository.save(resTx);

                if (statusHistoryRepository != null) {
                    TransactionStatusHistoryMaster hist = TransactionStatusHistoryMaster.builder()
                            .historyId(UUID.randomUUID().toString())
                            .transactionId(txId)
                            .fromStatus("INITIATED")
                            .toStatus("RESERVED")
                            .changeReason("AMOUNT_HOLD_APPLIED")
                            .actorId("T24_CORE_ORCHESTRATOR")
                            .actorType("SYSTEM")
                            .changedAt(now)
                            .build();
                    statusHistoryRepository.save(hist);
                }
            } catch (Exception e) {
                log.warn("Could not record provisional transaction for hold {}: {}", holdId, e.getMessage());
            }
        }

        String ofsResponse = OfsMessageUtil.buildOfsLockedEventResponse(
                lockRef, request.getAccountId(), request.getHoldAmount(), holdId
        );

        log.info("Amount hold created successfully: holdId={}, account={}, txId={}, newHoldTotal={}, availableRemaining={}",
                holdId, request.getAccountId(), txId, newHold, newAvailable);

        return AmountHoldResponseDto.builder()
                .holdId(holdId)
                .accountId(request.getAccountId())
                .holdAmount(request.getHoldAmount())
                .status("ACTIVE")
                .t24LockReference(lockRef)
                .externalReference(extRef)
                .transactionId(txId)
                .targetAccountId(request.getTargetAccountId())
                .expiresAt(expiresAt)
                .createdAt(now)
                .ofsResponse(ofsResponse)
                .message("Hold placed successfully. Funds reserved for transaction " + txId)
                .build();
    }

    @Transactional("masterTransactionManager")
    public AmountHoldCaptureResponseDto captureHold(String holdId, AmountHoldCaptureRequestDto request) {
        log.info("Capturing amount hold into posted settlement: holdId={}", holdId);

        AmountHoldMaster hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new IllegalArgumentException("Amount hold not found for ID: " + holdId));

        if (!"ACTIVE".equalsIgnoreCase(hold.getStatus())) {
            throw new IllegalStateException("Cannot capture hold " + holdId + ". Current status is: " + hold.getStatus());
        }

        String sourceAccountId = hold.getAccountId();
        String targetAccountId = request != null && request.getTargetAccountId() != null && !request.getTargetAccountId().isBlank()
                ? request.getTargetAccountId()
                : null;

        // Parse linked transaction ID and target account from externalReference
        String extRef = hold.getExternalReference() != null ? hold.getExternalReference() : "";
        String txId = "TX-SETTLE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        if (extRef.contains("|")) {
            String[] parts = extRef.split("\\|", 2);
            txId = parts[0];
            if (targetAccountId == null) {
                targetAccountId = parts[1];
            }
        } else if (!extRef.isBlank()) {
            txId = extRef;
        }

        if (targetAccountId == null || targetAccountId.isBlank()) {
            targetAccountId = "ACC-1002"; // default beneficiary if none specified
        }

        if (sourceAccountId.equalsIgnoreCase(targetAccountId)) {
            throw new IllegalArgumentException("Source and destination accounts cannot be identical.");
        }

        BigDecimal captureAmount = (request != null && request.getCaptureAmount() != null && request.getCaptureAmount().compareTo(BigDecimal.ZERO) > 0)
                ? request.getCaptureAmount()
                : hold.getHoldAmount();

        // 1. Lock accounts in deterministic order
        String firstLock = sourceAccountId.compareTo(targetAccountId) < 0 ? sourceAccountId : targetAccountId;
        String secondLock = sourceAccountId.compareTo(targetAccountId) < 0 ? targetAccountId : sourceAccountId;

        BalanceMaster firstBal = balanceRepository.findByAccountIdForUpdate(firstLock)
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + firstLock));
        BalanceMaster secondBal = balanceRepository.findByAccountIdForUpdate(secondLock)
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + secondLock));

        BalanceMaster srcBal = sourceAccountId.equals(firstLock) ? firstBal : secondBal;
        BalanceMaster dstBal = targetAccountId.equals(firstLock) ? firstBal : secondBal;

        Instant now = Instant.now();

        // 2. Debit source: reduce balance_amount AND release the hold_amount concurrently
        srcBal.setBalanceAmount(srcBal.getBalanceAmount().subtract(captureAmount));
        BigDecimal currentSrcHold = srcBal.getHoldAmount() != null ? srcBal.getHoldAmount() : BigDecimal.ZERO;
        BigDecimal newSrcHold = currentSrcHold.subtract(captureAmount);
        if (newSrcHold.compareTo(BigDecimal.ZERO) < 0) newSrcHold = BigDecimal.ZERO;
        srcBal.setHoldAmount(newSrcHold);
        srcBal.setAvailableBalance(srcBal.getBalanceAmount().subtract(newSrcHold));
        srcBal.setUpdatedAt(now);
        balanceRepository.save(srcBal);

        // 3. Credit destination account
        dstBal.setBalanceAmount(dstBal.getBalanceAmount().add(captureAmount));
        BigDecimal dstHold = dstBal.getHoldAmount() != null ? dstBal.getHoldAmount() : BigDecimal.ZERO;
        dstBal.setAvailableBalance(dstBal.getBalanceAmount().subtract(dstHold));
        dstBal.setUpdatedAt(now);
        balanceRepository.save(dstBal);

        // 4. Update hold status to CAPTURED
        hold.setStatus("CAPTURED");
        hold.setReleasedAt(now);
        holdRepository.save(hold);

        // 5. GL Double-Entry balancing entries
        if (glLedgerRepository != null) {
            try {
                GlLedgerMaster drEntry = GlLedgerMaster.builder()
                        .journalId(UUID.randomUUID().toString())
                        .transactionId(txId)
                        .glCode("20100")
                        .debitAmount(captureAmount)
                        .creditAmount(BigDecimal.ZERO)
                        .postingDate(LocalDate.now())
                        .createdAt(now)
                        .build();
                glLedgerRepository.save(drEntry);

                GlLedgerMaster crEntry = GlLedgerMaster.builder()
                        .journalId(UUID.randomUUID().toString())
                        .transactionId(txId)
                        .glCode("20100")
                        .debitAmount(BigDecimal.ZERO)
                        .creditAmount(captureAmount)
                        .postingDate(LocalDate.now())
                        .createdAt(now)
                        .build();
                glLedgerRepository.save(crEntry);
            } catch (Exception e) {
                log.warn("Could not record GL entries for captured hold {}: {}", holdId, e.getMessage());
            }
        }

        // 6. Record/update transaction master record
        if (transactionRepository != null) {
            try {
                TransactionMaster tx = TransactionMaster.builder()
                        .transactionId(txId)
                        .sourceAccountId(sourceAccountId)
                        .targetAccountId(targetAccountId)
                        .amount(captureAmount)
                        .currency("PHP")
                        .status("POSTED")
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                transactionRepository.save(tx);

                if (statusHistoryRepository != null) {
                    TransactionStatusHistoryMaster hist = TransactionStatusHistoryMaster.builder()
                            .historyId(UUID.randomUUID().toString())
                            .transactionId(txId)
                            .fromStatus("RESERVED")
                            .toStatus("POSTED")
                            .changeReason("HOLD_CAPTURED_SETTLED")
                            .actorId("T24_CORE_ORCHESTRATOR")
                            .actorType("SYSTEM")
                            .changedAt(now)
                            .build();
                    statusHistoryRepository.save(hist);
                }
            } catch (Exception e) {
                log.warn("Could not update transaction status for captured hold {}: {}", holdId, e.getMessage());
            }
        }

        String ofsResponse = OfsMessageUtil.buildOfsLockedEventCaptureResponse(
                txId, holdId, captureAmount, sourceAccountId, targetAccountId
        );

        log.info("Hold {} successfully captured into posted transfer {}: srcRemaining={}, dstNewBal={}",
                holdId, txId, srcBal.getBalanceAmount(), dstBal.getBalanceAmount());

        return AmountHoldCaptureResponseDto.builder()
                .holdId(holdId)
                .transactionId(txId)
                .sourceAccountId(sourceAccountId)
                .targetAccountId(targetAccountId)
                .settledAmount(captureAmount)
                .sourceNewBalance(srcBal.getBalanceAmount())
                .sourceNewAvailable(srcBal.getAvailableBalance())
                .targetNewBalance(dstBal.getBalanceAmount())
                .targetNewAvailable(dstBal.getAvailableBalance())
                .status("CAPTURED")
                .message("Hold captured and settled successfully into completed funds transfer.")
                .ofsResponse(ofsResponse)
                .capturedAt(now)
                .build();
    }

    @Transactional("masterTransactionManager")
    public AmountHoldReleaseResponseDto releaseHold(String holdId) {
        log.info("Releasing amount hold: holdId={}", holdId);

        AmountHoldMaster hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new IllegalArgumentException("Amount hold not found for ID: " + holdId));

        if (!"ACTIVE".equalsIgnoreCase(hold.getStatus())) {
            throw new IllegalStateException("Amount hold " + holdId + " is not in ACTIVE state. Current status: " + hold.getStatus());
        }

        // 1. Acquire pessimistic lock on account balance row
        BalanceMaster balance = balanceRepository.findByAccountIdForUpdate(hold.getAccountId())
                .orElseThrow(() -> new IllegalArgumentException("Account balance not found for ID: " + hold.getAccountId()));

        Instant now = Instant.now();
        BigDecimal curHold = balance.getHoldAmount() != null ? balance.getHoldAmount() : BigDecimal.ZERO;
        BigDecimal newHold = curHold.subtract(hold.getHoldAmount());
        if (newHold.compareTo(BigDecimal.ZERO) < 0) {
            newHold = BigDecimal.ZERO;
        }

        BigDecimal newAvailable = balance.getBalanceAmount().subtract(newHold);
        balance.setHoldAmount(newHold);
        balance.setAvailableBalance(newAvailable);
        balance.setUpdatedAt(now);
        balanceRepository.save(balance);

        // 2. Mark hold as RELEASED
        hold.setStatus("RELEASED");
        hold.setReleasedAt(now);
        holdRepository.save(hold);

        // 3. If a linked transaction was in RESERVED, update it to CANCELLED
        if (transactionRepository != null && hold.getExternalReference() != null) {
            try {
                String linkedTxId = hold.getExternalReference().contains("|")
                        ? hold.getExternalReference().split("\\|")[0]
                        : hold.getExternalReference();
                transactionRepository.findById(linkedTxId).ifPresent(tx -> {
                    if ("RESERVED".equalsIgnoreCase(tx.getStatus())) {
                        tx.setStatus("CANCELLED");
                        tx.setUpdatedAt(now);
                        transactionRepository.save(tx);

                        if (statusHistoryRepository != null) {
                            TransactionStatusHistoryMaster hist = TransactionStatusHistoryMaster.builder()
                                    .historyId(UUID.randomUUID().toString())
                                    .transactionId(linkedTxId)
                                    .fromStatus("RESERVED")
                                    .toStatus("CANCELLED")
                                    .changeReason("AMOUNT_HOLD_RELEASED_CANCELLED")
                                    .actorId("T24_CORE_ORCHESTRATOR")
                                    .actorType("SYSTEM")
                                    .changedAt(now)
                                    .build();
                            statusHistoryRepository.save(hist);
                        }
                    }
                });
            } catch (Exception e) {
                log.warn("Could not cancel transaction for released hold {}: {}", holdId, e.getMessage());
            }
        }

        String ofsResponse = OfsMessageUtil.buildOfsLockedEventReleaseResponse(
                hold.getT24LockReference() != null ? hold.getT24LockReference() : "ACLK000000",
                holdId
        );

        log.info("Amount hold released successfully: holdId={}, account={}, restoredAvailable={}",
                holdId, hold.getAccountId(), newAvailable);

        return AmountHoldReleaseResponseDto.builder()
                .holdId(holdId)
                .accountId(hold.getAccountId())
                .releasedAmount(hold.getHoldAmount())
                .status("RELEASED")
                .message("Hold released successfully. Funds restored to available balance.")
                .releasedAt(now)
                .ofsResponse(ofsResponse)
                .build();
    }

    @Transactional(readOnly = true)
    public List<AmountHoldMaster> getHoldsByAccount(String accountId) {
        return holdRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Transactional(readOnly = true)
    public AmountHoldMaster getHoldById(String holdId) {
        return holdRepository.findById(holdId)
                .orElseThrow(() -> new IllegalArgumentException("Amount hold not found for ID: " + holdId));
    }
}

