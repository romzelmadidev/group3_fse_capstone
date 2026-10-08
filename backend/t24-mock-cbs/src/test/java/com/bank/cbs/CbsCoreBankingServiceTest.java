package com.bank.cbs;

import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.entity.master.*;
import com.bank.cbs.repository.master.*;
import com.bank.cbs.service.CbsCobBatchService;
import com.bank.cbs.service.CbsFundsTransferService;
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
    private AmountHoldMasterRepository holdRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private ObjectMapper objectMapper;
    private CbsFundsTransferService transferService;
    private CbsReversalService reversalService;
    private CbsCobBatchService cobBatchService;
    private com.bank.cbs.service.CbsAmountHoldService holdService;
    private com.bank.cbs.controller.CbsPostingController postingController;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

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

        holdService = new com.bank.cbs.service.CbsAmountHoldService(holdRepository, balanceRepository);
        postingController = new com.bank.cbs.controller.CbsPostingController(
                transferService, reversalService, holdService, balanceRepository
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
    void testAmountHold_SuccessfulCreation() {
        BalanceMaster accBal = BalanceMaster.builder()
                .accountId("ACC-HOLD-1")
                .balanceAmount(new BigDecimal("10000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("10000.00"))
                .build();
        when(balanceRepository.findByAccountIdForUpdate("ACC-HOLD-1")).thenReturn(Optional.of(accBal));

        com.bank.ledger.contracts.dto.AmountHoldRequestDto request = com.bank.ledger.contracts.dto.AmountHoldRequestDto.builder()
                .accountId("ACC-HOLD-1")
                .holdAmount(new BigDecimal("3000.00"))
                .reason("CARD_PREAUTH")
                .expiryHours(48)
                .externalReference("AUTH-999")
                .build();

        var response = holdService.createHold(request);

        assertNotNull(response);
        assertEquals("ACTIVE", response.getStatus());
        assertEquals("ACC-HOLD-1", response.getAccountId());
        assertEquals(new BigDecimal("3000.00"), response.getHoldAmount());
        assertEquals(new BigDecimal("3000.00"), accBal.getHoldAmount());
        assertEquals(new BigDecimal("7000.00"), accBal.getAvailableBalance());
        assertEquals(new BigDecimal("10000.00"), accBal.getBalanceAmount()); // Ledger balance untouched!
        assertTrue(response.getOfsResponse().contains("ACLK"));
        verify(holdRepository, times(1)).save(any());
    }

    @Test
    void testAmountHold_InsufficientAvailableFunds_ThrowsException() {
        BalanceMaster accBal = BalanceMaster.builder()
                .accountId("ACC-HOLD-2")
                .balanceAmount(new BigDecimal("5000.00"))
                .holdAmount(new BigDecimal("4000.00"))
                .availableBalance(new BigDecimal("1000.00"))
                .build();
        when(balanceRepository.findByAccountIdForUpdate("ACC-HOLD-2")).thenReturn(Optional.of(accBal));

        com.bank.ledger.contracts.dto.AmountHoldRequestDto request = com.bank.ledger.contracts.dto.AmountHoldRequestDto.builder()
                .accountId("ACC-HOLD-2")
                .holdAmount(new BigDecimal("2000.00")) // Exceeds available 1000.00
                .build();

        assertThrows(IllegalArgumentException.class, () -> holdService.createHold(request));
    }

    @Test
    void testAmountHold_ReleaseHold_RestoresAvailableBalance() {
        AmountHoldMaster hold = AmountHoldMaster.builder()
                .holdId("HLD-100")
                .accountId("ACC-HOLD-3")
                .holdAmount(new BigDecimal("2500.00"))
                .status("ACTIVE")
                .t24LockReference("ACLK100")
                .build();

        BalanceMaster accBal = BalanceMaster.builder()
                .accountId("ACC-HOLD-3")
                .balanceAmount(new BigDecimal("10000.00"))
                .holdAmount(new BigDecimal("2500.00"))
                .availableBalance(new BigDecimal("7500.00"))
                .build();

        when(holdRepository.findById("HLD-100")).thenReturn(Optional.of(hold));
        when(balanceRepository.findByAccountIdForUpdate("ACC-HOLD-3")).thenReturn(Optional.of(accBal));

        var releaseResponse = holdService.releaseHold("HLD-100");

        assertNotNull(releaseResponse);
        assertEquals("RELEASED", releaseResponse.getStatus());
        assertEquals(new BigDecimal("2500.00"), releaseResponse.getReleasedAmount());
        assertEquals(0, BigDecimal.ZERO.compareTo(accBal.getHoldAmount()));
        assertEquals(new BigDecimal("10000.00"), accBal.getAvailableBalance());
        assertTrue(releaseResponse.getOfsResponse().contains("HOLD_RELEASED"));
    }

    @Test
    void testOfsExecution_AmountHoldAndRelease() {
        BalanceMaster accBal = BalanceMaster.builder()
                .accountId("ACC-OFS-1")
                .balanceAmount(new BigDecimal("15000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("15000.00"))
                .build();
        when(balanceRepository.findByAccountIdForUpdate("ACC-OFS-1")).thenReturn(Optional.of(accBal));

        // 1. OFS AC.LOCKED.EVENTS,INPUT
        String ofsHoldRequest = "AC.LOCKED.EVENTS,INPUT/I/PROCESS/0/1,USER01/123456,,ACCOUNT.NUMBER=ACC-OFS-1,FROM.DATE=20261008,TO.DATE=20261009,LOCKED.AMOUNT=5000.00,HOLD.REASON=COURT_ORDER_FREEZE,EXT.REF=COURT-77";
        var holdRespEntity = postingController.executeOfs(ofsHoldRequest);

        assertEquals(200, holdRespEntity.getStatusCode().value());
        assertTrue(holdRespEntity.getBody().contains("LOCKED.AMOUNT:1:1=5000.00"));
        assertTrue(holdRespEntity.getBody().contains("HOLD.REF:1:1=HLD-"));
        assertEquals(new BigDecimal("5000.00"), accBal.getHoldAmount());
        assertEquals(new BigDecimal("10000.00"), accBal.getAvailableBalance());

        // Extract hold ID from response
        String respBody = holdRespEntity.getBody();
        String holdId = respBody.substring(respBody.indexOf("HOLD.REF:1:1=") + 13).trim();

        AmountHoldMaster holdRecord = AmountHoldMaster.builder()
                .holdId(holdId)
                .accountId("ACC-OFS-1")
                .holdAmount(new BigDecimal("5000.00"))
                .status("ACTIVE")
                .t24LockReference("ACLK-TEST")
                .build();
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(holdRecord));

        // 2. OFS AC.LOCKED.EVENTS,REVERSE
        String ofsReleaseRequest = "AC.LOCKED.EVENTS,REVERSE/I/PROCESS/0/1,USER01/123456,,HOLD.REF=" + holdId + ",ACCOUNT.NUMBER=ACC-OFS-1";
        var releaseRespEntity = postingController.executeOfs(ofsReleaseRequest);

        assertEquals(200, releaseRespEntity.getStatusCode().value());
        assertTrue(releaseRespEntity.getBody().contains("HOLD_RELEASED"));
        assertEquals(0, BigDecimal.ZERO.compareTo(accBal.getHoldAmount()));
        assertEquals(new BigDecimal("15000.00"), accBal.getAvailableBalance());
    }

    @Test
    void testOfsExecution_Reversal() {
        TransactionMaster origTx = TransactionMaster.builder()
                .transactionId("TXN-OFS-REV")
                .sourceAccountId("ACC-1")
                .targetAccountId("ACC-2")
                .amount(new BigDecimal("1000.00"))
                .currency("PHP")
                .status(TransactionStatus.Posted.name())
                .build();

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

        when(transactionRepository.findById("TXN-OFS-REV")).thenReturn(Optional.of(origTx));
        when(reversalRequestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reversalRequestRepository.findById(any())).thenAnswer(invocation -> Optional.of(
                ReversalRequestMaster.builder()
                        .ticketId(invocation.getArgument(0))
                        .originalTxId("TXN-OFS-REV")
                        .makerId("TELLER_MAKER")
                        .status("PENDING")
                        .build()
        ));
        when(balanceRepository.findByAccountIdForUpdate("ACC-1")).thenReturn(Optional.of(senderBal));
        when(balanceRepository.findByAccountIdForUpdate("ACC-2")).thenReturn(Optional.of(beneficiaryBal));

        String ofsRevRequest = "FUNDS.TRANSFER,REVERSAL/I/PROCESS//REV-TICKET-1,MGR02/123456,ORIGINAL.FT.NO=TXN-OFS-REV";
        var revRespEntity = postingController.executeOfs(ofsRevRequest);

        assertEquals(200, revRespEntity.getStatusCode().value());
        assertTrue(revRespEntity.getBody().contains("REVERSAL_APPROVED_AND_SETTLED"));
        assertEquals(new BigDecimal("6000.00"), senderBal.getBalanceAmount());
        assertEquals(new BigDecimal("3000.00"), beneficiaryBal.getBalanceAmount());
        assertEquals(TransactionStatus.Reversed.name(), origTx.getStatus());
    }
}

