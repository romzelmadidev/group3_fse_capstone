package com.bank.cbs;

import com.bank.cbs.dto.HoldRequestDto;
import com.bank.cbs.dto.HoldResponseDto;
import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.entity.master.*;
import com.bank.cbs.repository.master.*;
import com.bank.cbs.service.CbsCobBatchService;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsHoldService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.enums.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsCoreBankingServiceTest {

    @Mock
    private BalanceMasterRepository balanceRepository;
    @Mock
    private TransactionMasterRepository transactionRepository;
    @Mock
    private GlLedgerMasterRepository glLedgerRepository;
    @Mock
    private GlBalanceMasterRepository glBalanceRepository;
    @Mock
    private SystemDateMasterRepository systemDateRepository;
    @Mock
    private TransactionStatusHistoryMasterRepository statusHistoryRepository;
    @Mock
    private OutboxEventMasterRepository outboxRepository;
    @Mock
    private ReversalRequestMasterRepository reversalRequestRepository;
    @Mock
    private CobBatchLogMasterRepository cobBatchLogRepository;
    @Mock
    private UncollectedFeeMasterRepository uncollectedFeeRepository;
    @Mock
    private InterestAccrualMasterRepository interestAccrualRepository;
    @Mock
    private EodBalanceSnapshotMasterRepository eodSnapshotRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ObjectMapper objectMapper;
    private CbsFundsTransferService transferService;
    private CbsReversalService reversalService;
    private CbsCobBatchService cobBatchService;
    private CbsHoldService holdService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        holdService = new CbsHoldService(balanceRepository);

        transferService = new CbsFundsTransferService(
                balanceRepository, transactionRepository, glLedgerRepository, glBalanceRepository,
                systemDateRepository, statusHistoryRepository, outboxRepository,
                kafkaTemplate, objectMapper
        );

        reversalService = new CbsReversalService(
                reversalRequestRepository, transactionRepository, balanceRepository,
                glLedgerRepository, statusHistoryRepository, outboxRepository,
                kafkaTemplate, objectMapper
        );

        cobBatchService = new CbsCobBatchService(
                systemDateRepository, cobBatchLogRepository, balanceRepository,
                uncollectedFeeRepository, interestAccrualRepository, eodSnapshotRepository,
                glLedgerRepository, kafkaTemplate
        );
    }

    @Test
    void testFundsTransfer_SuccessfulPosting() {
        SystemDateMaster sysDate = SystemDateMaster.builder()
                .systemDateId("SYS-1")
                .businessDate(LocalDate.now())
                .status("ONLINE")
                .postingWindowOpen(true)
                .build();
        when(systemDateRepository.findTopByOrderBySystemDateIdAsc()).thenReturn(Optional.of(sysDate));

        BalanceMaster sourceBal = BalanceMaster.builder()
                .accountId("ACC-1")
                .balanceAmount(new BigDecimal("10000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("10000.00"))
                .build();

        BalanceMaster destBal = BalanceMaster.builder()
                .accountId("ACC-2")
                .balanceAmount(new BigDecimal("2000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("2000.00"))
                .build();

        when(balanceRepository.findByAccountIdForUpdate("ACC-1")).thenReturn(Optional.of(sourceBal));
        when(balanceRepository.findByAccountIdForUpdate("ACC-2")).thenReturn(Optional.of(destBal));

        TransferRequestDto request = new TransferRequestDto(
                "TXN-1", "ACC-1", "ACC-2",
                new BigDecimal("3000.00"), "PHP", "Test Transfer", "WEB", "IDEMP-1"
        );

        TransferResponseDto response = transferService.executeTransfer(request);

        assertNotNull(response);
        assertEquals(TransactionStatus.Posted.name(), response.status());
        assertEquals(new BigDecimal("7000.00"), sourceBal.getBalanceAmount());
        assertEquals(new BigDecimal("5000.00"), destBal.getBalanceAmount());
        verify(transactionRepository, times(1)).save(any());
        verify(glLedgerRepository, times(2)).save(any()); // 1 DR + 1 CR
    }

    @Test
    void testFundsTransfer_IdempotentReplay_BypassesBalanceMutation() {
        TransactionMaster existingTx = TransactionMaster.builder()
                .transactionId("TXN-ORIGINAL")
                .idempotencyKey("IDEMP-EXISTING")
                .sourceAccountId("ACC-1")
                .targetAccountId("ACC-2")
                .amount(new BigDecimal("3000.00"))
                .currency("PHP")
                .status(TransactionStatus.Posted.name())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(transactionRepository.findByIdempotencyKey("IDEMP-EXISTING")).thenReturn(Optional.of(existingTx));

        BalanceMaster sourceBal = BalanceMaster.builder().accountId("ACC-1").balanceAmount(new BigDecimal("7000.00")).build();
        BalanceMaster destBal = BalanceMaster.builder().accountId("ACC-2").balanceAmount(new BigDecimal("5000.00")).build();
        when(balanceRepository.findById("ACC-1")).thenReturn(Optional.of(sourceBal));
        when(balanceRepository.findById("ACC-2")).thenReturn(Optional.of(destBal));

        TransferRequestDto request = new TransferRequestDto(
                "TXN-NEW", "ACC-1", "ACC-2",
                new BigDecimal("3000.00"), "PHP", "Replayed Transfer", "WEB", "IDEMP-EXISTING"
        );

        TransferResponseDto response = transferService.executeTransfer(request);

        assertNotNull(response);
        assertEquals("TXN-ORIGINAL", response.transactionId());
        assertEquals(TransactionStatus.Posted.name(), response.status());
        assertEquals(new BigDecimal("3000.00"), response.amount());
        assertTrue(response.ofsResponse().contains("IDEMPOTENT_REPLAY"));

        // Verify that balances were NOT locked or mutated again
        verify(balanceRepository, never()).findByAccountIdForUpdate(anyString());
        verify(glLedgerRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void testFundsTransfer_InsufficientFunds_ThrowsException() {
        SystemDateMaster sysDate = SystemDateMaster.builder()
                .systemDateId("SYS-1")
                .businessDate(LocalDate.now())
                .status("ONLINE")
                .postingWindowOpen(true)
                .build();
        when(systemDateRepository.findTopByOrderBySystemDateIdAsc()).thenReturn(Optional.of(sysDate));

        BalanceMaster sourceBal = BalanceMaster.builder()
                .accountId("ACC-1")
                .balanceAmount(new BigDecimal("500.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("500.00"))
                .build();

        BalanceMaster destBal = BalanceMaster.builder()
                .accountId("ACC-2")
                .balanceAmount(new BigDecimal("2000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("2000.00"))
                .build();

        when(balanceRepository.findByAccountIdForUpdate("ACC-1")).thenReturn(Optional.of(sourceBal));
        when(balanceRepository.findByAccountIdForUpdate("ACC-2")).thenReturn(Optional.of(destBal));

        TransferRequestDto request = new TransferRequestDto(
                "TXN-2", "ACC-1", "ACC-2",
                new BigDecimal("1000.00"), "PHP", "Overdraft attempt", "WEB", "IDEMP-2"
        );

        assertThrows(IllegalArgumentException.class, () -> transferService.executeTransfer(request));
    }

    @Test
    void testReversal_DualControlViolation_ThrowsException() {
        ReversalRequestMaster revReq = ReversalRequestMaster.builder()
                .ticketId("TICKET-1")
                .originalTxId("TXN-1")
                .makerId("MAKER-1")
                .status("PENDING")
                .build();

        when(reversalRequestRepository.findById("TICKET-1")).thenReturn(Optional.of(revReq));

        ReversalActionDto action = new ReversalActionDto("TICKET-1", "MAKER-1", null, "Self-approval attempt");

        assertThrows(IllegalArgumentException.class, () -> reversalService.approveReversal(action));
    }

    @Test
    void testReversal_ApprovedByChecker_SuccessfullyReverses() {
        ReversalRequestMaster revReq = ReversalRequestMaster.builder()
                .ticketId("TICKET-1")
                .originalTxId("TXN-1")
                .makerId("MAKER-1")
                .status("PENDING")
                .build();

        when(reversalRequestRepository.findById("TICKET-1")).thenReturn(Optional.of(revReq));
        when(reversalRequestRepository.save(any())).thenReturn(revReq);

        TransactionMaster origTx = TransactionMaster.builder()
                .transactionId("TXN-1")
                .sourceAccountId("ACC-1")
                .targetAccountId("ACC-2")
                .amount(new BigDecimal("2000.00"))
                .currency("PHP")
                .status(TransactionStatus.Posted.name())
                .build();

        when(transactionRepository.findById("TXN-1")).thenReturn(Optional.of(origTx));

        BalanceMaster senderBal = BalanceMaster.builder()
                .accountId("ACC-1")
                .balanceAmount(new BigDecimal("5000.00"))
                .availableBalance(new BigDecimal("5000.00"))
                .build();

        BalanceMaster beneficiaryBal = BalanceMaster.builder()
                .accountId("ACC-2")
                .balanceAmount(new BigDecimal("4000.00"))
                .availableBalance(new BigDecimal("4000.00"))
                .build();

        when(balanceRepository.findByAccountIdForUpdate("ACC-1")).thenReturn(Optional.of(senderBal));
        when(balanceRepository.findByAccountIdForUpdate("ACC-2")).thenReturn(Optional.of(beneficiaryBal));

        ReversalActionDto action = new ReversalActionDto("TICKET-1", "CHECKER-MGR", null, "Approved dispute");
        ReversalRequestMaster result = reversalService.approveReversal(action);

        assertNotNull(result);
        assertEquals("APPROVED", result.getStatus());
        assertEquals("CHECKER-MGR", result.getCheckerId());
        assertEquals(new BigDecimal("7000.00"), senderBal.getBalanceAmount());
        assertEquals(new BigDecimal("2000.00"), beneficiaryBal.getBalanceAmount());
        assertEquals(TransactionStatus.Reversed.name(), origTx.getStatus());
    }

    @Test
    void testCobBatch_ExecutesAllPhasesSuccessfully() {
        LocalDate today = LocalDate.of(2026, 10, 8);
        SystemDateMaster sysDate = SystemDateMaster.builder()
                .systemDateId("SYS-1")
                .businessDate(today)
                .status("ONLINE")
                .postingWindowOpen(true)
                .build();
        when(systemDateRepository.findTopByOrderBySystemDateIdAsc()).thenReturn(Optional.of(sysDate));

        BalanceMaster b1 = BalanceMaster.builder()
                .accountId("ACC-LOW")
                .balanceAmount(new BigDecimal("30.00")) // below min 5000, and < 50 fee -> zero-overdraft test
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("30.00"))
                .build();

        BalanceMaster b2 = BalanceMaster.builder()
                .accountId("ACC-NORMAL")
                .balanceAmount(new BigDecimal("100000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("100000.00"))
                .build();

        when(balanceRepository.findAll()).thenReturn(List.of(b1, b2));
        when(glLedgerRepository.sumTotalDebitsForDate(today)).thenReturn(new BigDecimal("50000.00"));
        when(glLedgerRepository.sumTotalCreditsForDate(today)).thenReturn(new BigDecimal("50000.00")); // balanced!

        var response = cobBatchService.runCobBatch();

        assertNotNull(response);
        assertEquals(today.plusDays(1), response.businessDate()); // Rolled over to T+1
        assertEquals("ONLINE", response.status());
        assertTrue(response.postingWindowOpen());
        assertEquals(5, response.phaseResults().size()); // 5 phases completed
        assertEquals("0.00", b1.getBalanceAmount().toPlainString()); // Protected from overdraft
        verify(uncollectedFeeRepository, times(1)).save(any()); // 20.00 uncollected fee logged
    }

    @Test
    void testCbsHoldService_PlaceAndReleaseHold_ModifiesAvailableBalance() {
        BalanceMaster bal = BalanceMaster.builder()
                .accountId("ACC-HOLD-TEST")
                .balanceAmount(new BigDecimal("300000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("300000.00"))
                .build();

        when(balanceRepository.findByAccountIdForUpdate("ACC-HOLD-TEST")).thenReturn(Optional.of(bal));

        // 1. Place hold of 250,000
        HoldRequestDto holdReq = new HoldRequestDto(
                "HOLD-REF-1", "ACC-HOLD-TEST", new BigDecimal("250000.00"), "PHP", "ANTI_SCAM_COOLING_OFF"
        );
        HoldResponseDto holdResp = holdService.placeHold(holdReq);

        assertNotNull(holdResp);
        assertEquals("HELD", holdResp.status());
        assertEquals(new BigDecimal("300000.00"), bal.getBalanceAmount());
        assertEquals(new BigDecimal("250000.00"), bal.getHoldAmount());
        assertEquals(new BigDecimal("50000.00"), bal.getAvailableBalance());
        verify(balanceRepository, times(1)).save(bal);

        // 2. Release hold of 250,000
        HoldResponseDto releaseResp = holdService.releaseHold(holdReq);

        assertNotNull(releaseResp);
        assertEquals("RELEASED", releaseResp.status());
        assertEquals(new BigDecimal("300000.00"), bal.getBalanceAmount());
        assertEquals(0, BigDecimal.ZERO.compareTo(bal.getHoldAmount()));
        assertEquals(new BigDecimal("300000.00"), bal.getAvailableBalance());
        verify(balanceRepository, times(2)).save(bal);
    }

    @Test
    void testFundsTransfer_WithPreHeldFunds_SettlesHoldAndDebitsBalance() {
        SystemDateMaster sysDate = SystemDateMaster.builder()
                .systemDateId("SYS-1")
                .businessDate(LocalDate.now())
                .status("ONLINE")
                .postingWindowOpen(true)
                .build();
        when(systemDateRepository.findTopByOrderBySystemDateIdAsc()).thenReturn(Optional.of(sysDate));

        // Source account has 300,000 total balance with 250,000 already on hold (50,000 available)
        BalanceMaster sourceBal = BalanceMaster.builder()
                .accountId("ACC-SRC-HELD")
                .balanceAmount(new BigDecimal("300000.00"))
                .holdAmount(new BigDecimal("250000.00"))
                .availableBalance(new BigDecimal("50000.00"))
                .build();

        BalanceMaster destBal = BalanceMaster.builder()
                .accountId("ACC-DEST-1")
                .balanceAmount(new BigDecimal("10000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("10000.00"))
                .build();

        when(balanceRepository.findByAccountIdForUpdate("ACC-SRC-HELD")).thenReturn(Optional.of(sourceBal));
        when(balanceRepository.findByAccountIdForUpdate("ACC-DEST-1")).thenReturn(Optional.of(destBal));

        TransferRequestDto request = new TransferRequestDto(
                "TXN-SETTLE-HOLD", "ACC-SRC-HELD", "ACC-DEST-1",
                new BigDecimal("250000.00"), "PHP", "Settling cooled-off transfer", "ORCHESTRATOR", "IDEMP-HELD",
                true // fundsHeld = true
        );

        TransferResponseDto response = transferService.executeTransfer(request);

        assertNotNull(response);
        assertEquals(TransactionStatus.Posted.name(), response.status());

        // Balance settles: 300,000 - 250,000 = 50,000; hold: 250,000 - 250,000 = 0; available: 50,000 - 0 = 50,000
        assertEquals(new BigDecimal("50000.00"), sourceBal.getBalanceAmount());
        assertEquals(0, BigDecimal.ZERO.compareTo(sourceBal.getHoldAmount()));
        assertEquals(new BigDecimal("50000.00"), sourceBal.getAvailableBalance());

        // Destination receives 250,000
        assertEquals(new BigDecimal("260000.00"), destBal.getBalanceAmount());
        assertEquals(new BigDecimal("260000.00"), destBal.getAvailableBalance());

        verify(transactionRepository, times(1)).save(any());
        verify(glLedgerRepository, times(2)).save(any());
    }

    @Test
    void testCobBatch_ClearedAdbInterest_ExcludesHoldAmount() {
        LocalDate cobDate = LocalDate.of(2026, 10, 8);
        SystemDateMaster sysDate = SystemDateMaster.builder()
                .systemDateId("SYS-1")
                .businessDate(cobDate)
                .status("ONLINE")
                .postingWindowOpen(true)
                .build();
        when(systemDateRepository.findTopByOrderBySystemDateIdAsc()).thenReturn(Optional.of(sysDate));

        // Account has 100,000 balance with 40,000 on hold (cleared = 60,000)
        BalanceMaster bal = BalanceMaster.builder()
                .accountId("ACC-CLEARED-TEST")
                .balanceAmount(new BigDecimal("100000.00"))
                .holdAmount(new BigDecimal("40000.00"))
                .availableBalance(new BigDecimal("60000.00"))
                .build();

        when(balanceRepository.findAll()).thenReturn(List.of(bal));
        when(glLedgerRepository.sumTotalDebitsForDate(cobDate)).thenReturn(BigDecimal.ZERO);
        when(glLedgerRepository.sumTotalCreditsForDate(cobDate)).thenReturn(BigDecimal.ZERO);

        cobBatchService.runCobBatch();

        // Capture interest accrual to verify calculated on cleared 60,000 (not 100,000)
        org.mockito.ArgumentCaptor<InterestAccrualMaster> captor =
                org.mockito.ArgumentCaptor.forClass(InterestAccrualMaster.class);
        verify(interestAccrualRepository, times(1)).save(captor.capture());

        InterestAccrualMaster savedAccrual = captor.getValue();
        // Daily rate: 0.005 / 365 = ~0.0000136986
        // Gross on 60,000 = 60,000 * (0.005 / 365) = 0.8219
        // Gross on 100,000 would have been 1.3699
        BigDecimal expectedGross = new BigDecimal("60000.00")
                .multiply(new BigDecimal("0.005").divide(BigDecimal.valueOf(365), 10, java.math.RoundingMode.HALF_UP))
                .setScale(4, java.math.RoundingMode.HALF_UP);

        assertEquals(expectedGross, savedAccrual.getAccruedAmount());
    }

    @Test
    void testCobBatch_AutomatedArrearsSweep_RecoversPendingFee() {
        LocalDate cobDate = LocalDate.of(2026, 10, 8);
        SystemDateMaster sysDate = SystemDateMaster.builder()
                .systemDateId("SYS-1")
                .businessDate(cobDate)
                .status("ONLINE")
                .postingWindowOpen(true)
                .build();
        when(systemDateRepository.findTopByOrderBySystemDateIdAsc()).thenReturn(Optional.of(sysDate));

        // Account has 500.00 balance (above 0, can pay arrears)
        BalanceMaster bal = BalanceMaster.builder()
                .accountId("ACC-ARREARS-TEST")
                .balanceAmount(new BigDecimal("500.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("500.00"))
                .build();

        // Unsettled fee of 50.00 with 0.00 collected so far
        UncollectedFeeMaster pendingFee = UncollectedFeeMaster.builder()
                .feeId("FEE-1")
                .accountId("ACC-ARREARS-TEST")
                .feeType("BELOW_MIN_ADB")
                .amountDue(new BigDecimal("50.00"))
                .amountCollected(BigDecimal.ZERO)
                .isSettled(false)
                .build();

        when(balanceRepository.findAll()).thenReturn(List.of(bal));
        when(balanceRepository.findById("ACC-ARREARS-TEST")).thenReturn(Optional.of(bal));
        when(uncollectedFeeRepository.findByIsSettledFalse()).thenReturn(List.of(pendingFee));
        when(glLedgerRepository.sumTotalDebitsForDate(cobDate)).thenReturn(BigDecimal.ZERO);
        when(glLedgerRepository.sumTotalCreditsForDate(cobDate)).thenReturn(BigDecimal.ZERO);

        cobBatchService.runCobBatch();

        // Arrears sweep should recover 50.00: balance 500 - 50 = 450.00
        assertEquals(new BigDecimal("400.00"), bal.getBalanceAmount()); // 50 from below-min fee + 50 from arrears sweep = 100 total
        assertTrue(pendingFee.getIsSettled());
        assertEquals(new BigDecimal("50.00"), pendingFee.getAmountCollected());
        verify(uncollectedFeeRepository, atLeastOnce()).save(pendingFee);
    }
}
