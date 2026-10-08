package com.bank.cbs.service;

import com.bank.cbs.config.KafkaConfig;
import com.bank.cbs.dto.CobExecutionResponseDto;
import com.bank.cbs.entity.master.*;
import com.bank.cbs.repository.master.*;
import com.bank.ledger.contracts.dto.events.BalanceSnapshotFrozenEvent;
import com.bank.ledger.contracts.dto.events.EodCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CbsCobBatchService {

    private static final Logger log = LoggerFactory.getLogger(CbsCobBatchService.class);

    private static final BigDecimal MIN_ADB_THRESHOLD = new BigDecimal("5000.00");
    private static final BigDecimal MONTHLY_FEE_AMOUNT = new BigDecimal("50.00");
    private static final BigDecimal ANNUAL_INTEREST_RATE = new BigDecimal("0.005"); // 0.5% p.a.
    private static final BigDecimal TAX_WITHHOLDING_RATE = new BigDecimal("0.20");  // 20% BIR tax

    private final SystemDateMasterRepository systemDateRepository;
    private final CobBatchLogMasterRepository cobBatchLogRepository;
    private final BalanceMasterRepository balanceRepository;
    private final UncollectedFeeMasterRepository uncollectedFeeRepository;
    private final InterestAccrualMasterRepository interestAccrualRepository;
    private final EodBalanceSnapshotMasterRepository eodSnapshotRepository;
    private final GlLedgerMasterRepository glLedgerRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CbsCobBatchService(
            SystemDateMasterRepository systemDateRepository,
            CobBatchLogMasterRepository cobBatchLogRepository,
            BalanceMasterRepository balanceRepository,
            UncollectedFeeMasterRepository uncollectedFeeRepository,
            InterestAccrualMasterRepository interestAccrualRepository,
            EodBalanceSnapshotMasterRepository eodSnapshotRepository,
            GlLedgerMasterRepository glLedgerRepository,
            KafkaTemplate<String, Object> kafkaTemplate) {
        this.systemDateRepository = systemDateRepository;
        this.cobBatchLogRepository = cobBatchLogRepository;
        this.balanceRepository = balanceRepository;
        this.uncollectedFeeRepository = uncollectedFeeRepository;
        this.interestAccrualRepository = interestAccrualRepository;
        this.eodSnapshotRepository = eodSnapshotRepository;
        this.glLedgerRepository = glLedgerRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional("masterTransactionManager")
    public CobExecutionResponseDto runCobBatch() {
        SystemDateMaster sysDate = systemDateRepository.findTopByOrderBySystemDateIdAsc()
                .orElseGet(() -> {
                    SystemDateMaster initDate = SystemDateMaster.builder()
                            .systemDateId("SYS-DATE-1")
                            .businessDate(LocalDate.now())
                            .status("ONLINE")
                            .postingWindowOpen(true)
                            .updatedAt(Instant.now())
                            .build();
                    return systemDateRepository.save(initDate);
                });

        LocalDate cobDate = sysDate.getBusinessDate();
        log.info("Starting Close of Business (COB) execution for date: {}", cobDate);

        List<CobExecutionResponseDto.CobPhaseResultDto> phaseResults = new ArrayList<>();

        try {
            // PHASE 0: Cutoff & Pre-COB validation
            phase0Cutoff(sysDate, cobDate, phaseResults);

            // PHASE 1: Below-Min ADB Fee Deduction
            phase1FeeDeduction(cobDate, phaseResults);

            // PHASE 2: Daily Interest Accrual & Tax Withholding
            phase2InterestAccrual(cobDate, phaseResults);

            // PHASE 3: Balance Snapshot & GL Reconciliation (with tripwire)
            phase3BalanceSnapshotAndGlRecon(cobDate, phaseResults);

            // PHASE 4: Rollover Business Date to T+1 & Open Window
            phase4DateRollover(sysDate, cobDate, phaseResults);

            log.info("COB execution finished successfully for date: {}", cobDate);
            return new CobExecutionResponseDto(sysDate.getBusinessDate(), sysDate.getStatus(), sysDate.getPostingWindowOpen(), phaseResults);

        } catch (Exception e) {
            log.error("COB Execution HALTED: {}", e.getMessage(), e);
            sysDate.setStatus("ERROR_HALTED");
            sysDate.setPostingWindowOpen(false);
            sysDate.setUpdatedAt(Instant.now());
            systemDateRepository.save(sysDate);
            throw new RuntimeException("COB batch halted due to critical error: " + e.getMessage(), e);
        }
    }

    private void phase0Cutoff(SystemDateMaster sysDate, LocalDate cobDate, List<CobExecutionResponseDto.CobPhaseResultDto> phaseResults) {
        log.info("Phase 0: Executing posting window cutoff");
        sysDate.setStatus("EOD_CUTOFF");
        sysDate.setPostingWindowOpen(false);
        sysDate.setUpdatedAt(Instant.now());
        systemDateRepository.save(sysDate);

        logCobPhase(cobDate, "Phase 0 - Cutoff", "COMPLETED", "Posting window closed. Status set to EOD_CUTOFF.");
        phaseResults.add(new CobExecutionResponseDto.CobPhaseResultDto(0, "Posting Cutoff", "COMPLETED", "Window closed."));
    }

    private void phase1FeeDeduction(LocalDate cobDate, List<CobExecutionResponseDto.CobPhaseResultDto> phaseResults) {
        log.info("Phase 1: Evaluating below-min ADB fees");
        List<BalanceMaster> allBalances = balanceRepository.findAll();
        int feesApplied = 0;
        int zeroOverdraftProtected = 0;
        BigDecimal totalFees = BigDecimal.ZERO;
        Instant now = Instant.now();

        for (BalanceMaster balance : allBalances) {
            if (balance.getBalanceAmount().compareTo(MIN_ADB_THRESHOLD) < 0 && balance.getBalanceAmount().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal balanceAmount = balance.getBalanceAmount();
                if (balanceAmount.compareTo(MONTHLY_FEE_AMOUNT) >= 0) {
                    balance.setBalanceAmount(balanceAmount.subtract(MONTHLY_FEE_AMOUNT));
                    BigDecimal hold = balance.getHoldAmount() != null ? balance.getHoldAmount() : BigDecimal.ZERO;
                    balance.setAvailableBalance(balance.getBalanceAmount().subtract(hold));
                    balance.setUpdatedAt(now);
                    balanceRepository.save(balance);
                    feesApplied++;
                    totalFees = totalFees.add(MONTHLY_FEE_AMOUNT);
                } else {
                    BigDecimal collected = balanceAmount;
                    BigDecimal uncollected = MONTHLY_FEE_AMOUNT.subtract(collected);
                    balance.setBalanceAmount(new BigDecimal("0.00"));
                    balance.setAvailableBalance(new BigDecimal("0.00"));
                    balance.setUpdatedAt(now);
                    balanceRepository.save(balance);

                    UncollectedFeeMaster uncollectedFee = UncollectedFeeMaster.builder()
                            .feeId(UUID.randomUUID().toString())
                            .accountId(balance.getAccountId())
                            .feeType("BELOW_MIN_ADB")
                            .amountDue(MONTHLY_FEE_AMOUNT)
                            .amountCollected(collected)
                            .isSettled(false)
                            .createdAt(now)
                            .build();
                    uncollectedFeeRepository.save(uncollectedFee);

                    zeroOverdraftProtected++;
                    totalFees = totalFees.add(collected);
                }
            }
        }

        String details = String.format("Fees applied: %d, Zero-overdraft protected: %d, Total collected: %s", feesApplied, zeroOverdraftProtected, totalFees);
        logCobPhase(cobDate, "Phase 1 - Below-Min ADB Fee", "COMPLETED", details);
        phaseResults.add(new CobExecutionResponseDto.CobPhaseResultDto(1, "Below-Min ADB Fee Deductions", "COMPLETED", details));
    }

    private void phase2InterestAccrual(LocalDate cobDate, List<CobExecutionResponseDto.CobPhaseResultDto> phaseResults) {
        log.info("Phase 2: Calculating daily interest accrual and tax withholding");
        List<BalanceMaster> allBalances = balanceRepository.findAll();
        int interestAccounts = 0;
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        Instant now = Instant.now();

        for (BalanceMaster balance : allBalances) {
            if (balance.getBalanceAmount().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dailyRate = ANNUAL_INTEREST_RATE.divide(new BigDecimal("365"), 10, RoundingMode.HALF_UP);
                BigDecimal grossInterest = balance.getBalanceAmount().multiply(dailyRate).setScale(4, RoundingMode.HALF_UP);
                BigDecimal withholdingTax = grossInterest.multiply(TAX_WITHHOLDING_RATE).setScale(4, RoundingMode.HALF_UP);
                BigDecimal netInterest = grossInterest.subtract(withholdingTax);

                if (grossInterest.compareTo(BigDecimal.ZERO) > 0) {
                    InterestAccrualMaster accrual = InterestAccrualMaster.builder()
                            .accrualId(UUID.randomUUID().toString())
                            .accountId(balance.getAccountId())
                            .accrualDate(cobDate)
                            .dailyRate(dailyRate)
                            .accruedAmount(grossInterest)
                            .taxWithheld(withholdingTax)
                            .netAccrual(netInterest)
                            .isCapitalized(false)
                            .createdAt(now)
                            .build();
                    interestAccrualRepository.save(accrual);

                    totalGross = totalGross.add(grossInterest);
                    totalTax = totalTax.add(withholdingTax);
                    interestAccounts++;
                }
            }
        }

        String details = String.format("Accrued interest for %d accounts. Gross: %s, Tax: %s", interestAccounts, totalGross, totalTax);
        logCobPhase(cobDate, "Phase 2 - Interest Accrual", "COMPLETED", details);
        phaseResults.add(new CobExecutionResponseDto.CobPhaseResultDto(2, "Daily Interest Accrual", "COMPLETED", details));
    }

    private void phase3BalanceSnapshotAndGlRecon(LocalDate cobDate, List<CobExecutionResponseDto.CobPhaseResultDto> phaseResults) {
        log.info("Phase 3: Balance snapshot freezing and GL Level 1 reconciliation");
        List<BalanceMaster> allBalances = balanceRepository.findAll();
        UUID snapshotBatchId = UUID.randomUUID();
        Instant now = Instant.now();

        for (BalanceMaster balance : allBalances) {
            EodBalanceSnapshotMaster snapshot = EodBalanceSnapshotMaster.builder()
                    .snapshotId(UUID.randomUUID().toString())
                    .accountId(balance.getAccountId())
                    .businessDate(cobDate)
                    .closingBalance(balance.getBalanceAmount())
                    .frozenAt(now)
                    .build();
            eodSnapshotRepository.save(snapshot);
        }

        // GL Level 1 Reconciliation: Total DR == Total CR in GlLedgerMaster
        BigDecimal totalDr = glLedgerRepository.sumTotalDebitsForDate(cobDate);
        BigDecimal totalCr = glLedgerRepository.sumTotalCreditsForDate(cobDate);
        if (totalDr == null) totalDr = BigDecimal.ZERO;
        if (totalCr == null) totalCr = BigDecimal.ZERO;

        BigDecimal variance = totalDr.subtract(totalCr);
        log.info("GL Reconciliation check: DR={}, CR={}, Variance={}", totalDr, totalCr, variance);

        if (variance.compareTo(BigDecimal.ZERO) != 0) {
            logCobPhase(cobDate, "Phase 3 - GL Reconciliation", "FAILED",
                    "GL Imbalance tripwire triggered! Variance=" + variance);
            throw new IllegalStateException("CRITICAL GL IMBALANCE: Total DR (" + totalDr + ") != Total CR (" + totalCr + ")");
        }

        // Publish batch events
        BalanceSnapshotFrozenEvent snapshotEvent = BalanceSnapshotFrozenEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .batchId(snapshotBatchId.toString())
                .businessDate(cobDate.toString())
                .snapshotsCount(allBalances.size())
                .frozenAtUtc(now)
                .build();
        kafkaTemplate.send(KafkaConfig.TOPIC_BATCH_EVENTS, cobDate.toString(), snapshotEvent);

        EodCompletedEvent completedEvent = EodCompletedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .batchId(snapshotBatchId.toString())
                .previousBusinessDate(cobDate.toString())
                .newBusinessDate(cobDate.plusDays(1).toString())
                .completedAtUtc(now)
                .build();
        kafkaTemplate.send(KafkaConfig.TOPIC_BATCH_EVENTS, cobDate.toString(), completedEvent);

        String details = String.format("Snapshots frozen: %d. GL DR: %s, GL CR: %s. Balanced perfectly.", allBalances.size(), totalDr, totalCr);
        logCobPhase(cobDate, "Phase 3 - Balance Snapshot & GL Recon", "COMPLETED", details);
        phaseResults.add(new CobExecutionResponseDto.CobPhaseResultDto(3, "Balance Snapshot & GL Recon", "COMPLETED", details));
    }

    private void phase4DateRollover(SystemDateMaster sysDate, LocalDate cobDate, List<CobExecutionResponseDto.CobPhaseResultDto> phaseResults) {
        log.info("Phase 4: Rolling over business date to T+1");
        LocalDate nextDate = cobDate.plusDays(1);
        sysDate.setBusinessDate(nextDate);
        sysDate.setStatus("ONLINE");
        sysDate.setPostingWindowOpen(true);
        sysDate.setLastCobCompletedAt(Instant.now());
        sysDate.setUpdatedAt(Instant.now());
        systemDateRepository.save(sysDate);

        String details = "Advanced business date from " + cobDate + " to " + nextDate + ". Posting window opened.";
        logCobPhase(cobDate, "Phase 4 - Business Date Rollover", "COMPLETED", details);
        phaseResults.add(new CobExecutionResponseDto.CobPhaseResultDto(4, "Date Rollover & Re-open", "COMPLETED", details));
    }

    private void logCobPhase(LocalDate cobDate, String phaseName, String status, String details) {
        CobBatchLogMaster logEntry = CobBatchLogMaster.builder()
                .batchId(UUID.randomUUID().toString())
                .businessDate(cobDate)
                .currentPhase(phaseName)
                .status(status)
                .errorMessage(details)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
        cobBatchLogRepository.save(logEntry);
    }
}
