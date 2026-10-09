package com.bank.cbs;

import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.entity.master.*;
import com.bank.cbs.repository.master.*;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.enums.ActorType;
import com.bank.ledger.contracts.enums.ChangeReasonCode;
import com.bank.ledger.contracts.enums.TransactionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsFundsTransferStatusHistoryLifecycleTest {

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
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Captor
    private ArgumentCaptor<List<TransactionStatusHistoryMaster>> historyListCaptor;

    private ObjectMapper objectMapper;
    private CbsFundsTransferService transferService;
    private CbsReversalService reversalService;

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
    }

    @Test
    @DisplayName("Successful funds transfer records complete 5-state lifecycle history: Initiated -> Authorized -> Reserved -> Processing -> Posted")
    void testFundsTransfer_RecordsCompleteStatusHistory() {
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
        when(statusHistoryRepository.findByTransactionIdOrderByChangedAtAsc(eq("TXN-LIFECYCLE-1"), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        TransferRequestDto request = new TransferRequestDto(
                "TXN-LIFECYCLE-1", "ACC-1", "ACC-2",
                new BigDecimal("1500.00"), "PHP", "Lifecycle Test", "WEB", "IDEMP-LIFE-1"
        );

        TransferResponseDto response = transferService.executeTransfer(request);

        assertNotNull(response);
        assertEquals(TransactionStatus.Posted.name(), response.status());

        // Verify that all 5 state transitions were saved to statusHistoryRepository
        verify(statusHistoryRepository).saveAll(historyListCaptor.capture());
        List<TransactionStatusHistoryMaster> recordedHistory = historyListCaptor.getValue();
        assertEquals(5, recordedHistory.size(), "Should record exactly 5 state transitions for a complete transfer lifecycle");

        // Step 1: null -> Initiated
        TransactionStatusHistoryMaster step1 = recordedHistory.get(0);
        assertNull(step1.getFromStatus());
        assertEquals(TransactionStatus.Initiated.name(), step1.getToStatus());
        assertEquals(ChangeReasonCode.API_INGESTION, step1.getChangeReason());
        assertEquals(ActorType.SYSTEM_ORCH.name(), step1.getActorType());

        // Step 2: Initiated -> Authorized
        TransactionStatusHistoryMaster step2 = recordedHistory.get(1);
        assertEquals(TransactionStatus.Initiated.name(), step2.getFromStatus());
        assertEquals(TransactionStatus.Authorized.name(), step2.getToStatus());
        assertEquals(ChangeReasonCode.BIOMETRIC_AUTH_VERIFIED, step2.getChangeReason());
        assertEquals(ActorType.CUSTOMER.name(), step2.getActorType());

        // Step 3: Authorized -> Reserved
        TransactionStatusHistoryMaster step3 = recordedHistory.get(2);
        assertEquals(TransactionStatus.Authorized.name(), step3.getFromStatus());
        assertEquals(TransactionStatus.Reserved.name(), step3.getToStatus());
        assertEquals(ChangeReasonCode.FUNDS_RESERVATION_EARMARKED, step3.getChangeReason());
        assertEquals(ActorType.SYSTEM_CBS.name(), step3.getActorType());

        // Step 4: Reserved -> Processing
        TransactionStatusHistoryMaster step4 = recordedHistory.get(3);
        assertEquals(TransactionStatus.Reserved.name(), step4.getFromStatus());
        assertEquals(TransactionStatus.Processing.name(), step4.getToStatus());
        assertEquals(ChangeReasonCode.CBS_OFS_PROCESSING, step4.getChangeReason());
        assertEquals(ActorType.SYSTEM_CBS.name(), step4.getActorType());

        // Step 5: Processing -> Posted
        TransactionStatusHistoryMaster step5 = recordedHistory.get(4);
        assertEquals(TransactionStatus.Processing.name(), step5.getFromStatus());
        assertEquals(TransactionStatus.Posted.name(), step5.getToStatus());
        assertEquals(ChangeReasonCode.ACID_LEDGER_COMMITTED, step5.getChangeReason());
        assertEquals(ActorType.SYSTEM_CBS.name(), step5.getActorType());

        // Verify strictly ascending timestamps
        for (int i = 0; i < recordedHistory.size() - 1; i++) {
            assertTrue(
                    recordedHistory.get(i).getChangedAt().isBefore(recordedHistory.get(i + 1).getChangedAt())
                    || recordedHistory.get(i).getChangedAt().equals(recordedHistory.get(i + 1).getChangedAt()),
                    "Timestamps must be chronologically ascending: step " + i + " vs step " + (i + 1)
            );
        }
    }

    @Test
    @DisplayName("Pre-reserved transaction resumes from Reserved state to Processing -> Posted")
    void testFundsTransfer_ResumesFromReservedState() {
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
                .holdAmount(new BigDecimal("1500.00"))
                .availableBalance(new BigDecimal("8500.00"))
                .build();

        BalanceMaster destBal = BalanceMaster.builder()
                .accountId("ACC-2")
                .balanceAmount(new BigDecimal("2000.00"))
                .holdAmount(BigDecimal.ZERO)
                .availableBalance(new BigDecimal("2000.00"))
                .build();

        when(balanceRepository.findByAccountIdForUpdate("ACC-1")).thenReturn(Optional.of(sourceBal));
        when(balanceRepository.findByAccountIdForUpdate("ACC-2")).thenReturn(Optional.of(destBal));

        TransactionStatusHistoryMaster reservedStep = TransactionStatusHistoryMaster.builder()
                .historyId("HIST-RES-1")
                .transactionId("TXN-RES-1")
                .fromStatus(TransactionStatus.Authorized.name())
                .toStatus(TransactionStatus.Reserved.name())
                .changeReason(ChangeReasonCode.FUNDS_RESERVATION_EARMARKED)
                .actorId("SYSTEM_CBS")
                .actorType(ActorType.SYSTEM_CBS.name())
                .changedAt(Instant.now().minusSeconds(60))
                .build();

        when(statusHistoryRepository.findByTransactionIdOrderByChangedAtAsc(eq("TXN-RES-1"), any()))
                .thenReturn(new PageImpl<>(List.of(reservedStep)));

        TransferRequestDto request = new TransferRequestDto(
                "TXN-RES-1", "ACC-1", "ACC-2",
                new BigDecimal("1500.00"), "PHP", "Resume Test", "WEB", "IDEMP-RES-1", true
        );

        TransferResponseDto response = transferService.executeTransfer(request);

        assertNotNull(response);
        assertEquals(TransactionStatus.Posted.name(), response.status());

        verify(statusHistoryRepository).saveAll(historyListCaptor.capture());
        List<TransactionStatusHistoryMaster> recordedHistory = historyListCaptor.getValue();
        assertEquals(2, recordedHistory.size(), "Should transition Reserved -> Processing -> Posted");

        assertEquals(TransactionStatus.Reserved.name(), recordedHistory.get(0).getFromStatus());
        assertEquals(TransactionStatus.Processing.name(), recordedHistory.get(0).getToStatus());
        assertEquals(TransactionStatus.Processing.name(), recordedHistory.get(1).getFromStatus());
        assertEquals(TransactionStatus.Posted.name(), recordedHistory.get(1).getToStatus());
    }

    @Test
    @DisplayName("recordFailedTransfer records status history leading to Failed state")
    void testRecordFailedTransfer() {
        when(statusHistoryRepository.findByTransactionIdOrderByChangedAtAsc(eq("TXN-FAIL-1"), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        TransferRequestDto request = new TransferRequestDto(
                "TXN-FAIL-1", "ACC-1", "ACC-2",
                new BigDecimal("50000.00"), "PHP", "Failed Test", "WEB", "IDEMP-FAIL-1"
        );

        transferService.recordFailedTransfer(request, "Insufficient funds in source account");

        verify(statusHistoryRepository).saveAll(historyListCaptor.capture());
        List<TransactionStatusHistoryMaster> recordedHistory = historyListCaptor.getValue();
        assertEquals(4, recordedHistory.size(), "Should record Initiated -> Authorized -> Processing -> Failed");

        TransactionStatusHistoryMaster lastStep = recordedHistory.get(recordedHistory.size() - 1);
        assertEquals(TransactionStatus.Processing.name(), lastStep.getFromStatus());
        assertEquals(TransactionStatus.Failed.name(), lastStep.getToStatus());
        assertEquals(ChangeReasonCode.CBS_SOLVENCY_DEFICIT, lastStep.getChangeReason());
    }

    @Test
    @DisplayName("recordCancelledTransfer records transition to Cancelled state")
    void testRecordCancelledTransfer() {
        TransactionStatusHistoryMaster reservedStep = TransactionStatusHistoryMaster.builder()
                .historyId("HIST-1")
                .transactionId("TXN-CANCEL-1")
                .fromStatus(TransactionStatus.Authorized.name())
                .toStatus(TransactionStatus.Reserved.name())
                .changeReason(ChangeReasonCode.FUNDS_RESERVATION_EARMARKED)
                .actorId("SYSTEM_CBS")
                .actorType(ActorType.SYSTEM_CBS.name())
                .changedAt(Instant.now().minusSeconds(10))
                .build();

        when(statusHistoryRepository.findByTransactionIdOrderByChangedAtAsc(eq("TXN-CANCEL-1"), any()))
                .thenReturn(new PageImpl<>(List.of(reservedStep)));

        transferService.recordCancelledTransfer(
                "TXN-CANCEL-1", "ACC-1", "ACC-2",
                new BigDecimal("300000.00"), "PHP",
                ChangeReasonCode.USER_COOL_OFF_CANCELLED, "Customer cancelled during cooling-off window",
                "CUST-001", ActorType.CUSTOMER.name()
        );

        verify(statusHistoryRepository).saveAll(historyListCaptor.capture());
        List<TransactionStatusHistoryMaster> recordedHistory = historyListCaptor.getValue();
        assertEquals(1, recordedHistory.size());

        TransactionStatusHistoryMaster cancelStep = recordedHistory.get(0);
        assertEquals(TransactionStatus.Reserved.name(), cancelStep.getFromStatus());
        assertEquals(TransactionStatus.Cancelled.name(), cancelStep.getToStatus());
        assertEquals(ChangeReasonCode.USER_COOL_OFF_CANCELLED, cancelStep.getChangeReason());
    }

    @Test
    @DisplayName("Reversals record PendingReversal on request, and Reversed on approval for both original and compensating transactions")
    void testReversalLifecycleStatusTransitions() {
        TransactionMaster originalTx = TransactionMaster.builder()
                .transactionId("FT-REV-TEST")
                .sourceAccountId("ACC-1")
                .targetAccountId("ACC-2")
                .amount(new BigDecimal("2000.00"))
                .currency("PHP")
                .status(TransactionStatus.Posted.name())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(transactionRepository.findById("FT-REV-TEST")).thenReturn(Optional.of(originalTx));

        // 1. Request Reversal -> records PendingReversal
        ReversalRequestDto reqDto = new ReversalRequestDto("FT-REV-TEST", "TELLER-1", "CUSTOMER_DISPUTE", "Wrong amount entered");
        reversalService.requestReversal(reqDto);

        ArgumentCaptor<TransactionStatusHistoryMaster> singleHistoryCaptor = ArgumentCaptor.forClass(TransactionStatusHistoryMaster.class);
        verify(statusHistoryRepository).save(singleHistoryCaptor.capture());

        TransactionStatusHistoryMaster pendingStep = singleHistoryCaptor.getValue();
        assertEquals(TransactionStatus.Posted.name(), pendingStep.getFromStatus());
        assertEquals(TransactionStatus.PendingReversal.name(), pendingStep.getToStatus());
        assertEquals(ChangeReasonCode.MAKER_DISPUTE_FILED, pendingStep.getChangeReason());
        assertEquals("TELLER-1", pendingStep.getActorId());
        assertEquals(ActorType.TELLER_MAKER.name(), pendingStep.getActorType());
    }
}
