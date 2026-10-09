package com.bank.orchestrator;

import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.dto.TransactionStatusHistoryDto;
import com.bank.orchestrator.controller.TransferOrchestratorController;
import com.bank.orchestrator.service.BiometricChallengeService;
import com.bank.orchestrator.service.CbsClientService;
import com.bank.orchestrator.service.CoolOffService;
import com.bank.orchestrator.service.TransferOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferOrchestratorTransactionEnquiryTest {

    @Mock
    private TransferOrchestrationService orchestrationService;

    @Mock
    private BiometricChallengeService biometricService;

    @Mock
    private CoolOffService coolOffService;

    @Mock
    private CbsClientService cbsService;

    @Test
    @DisplayName("GET /api/v1/transfers/accounts/{accountId}/transactions routes to CBS client via OFS")
    void testGetAccountTransactionsEndpoint() {
        TransferOrchestratorController controller = new TransferOrchestratorController(
                orchestrationService,
                biometricService,
                coolOffService,
                cbsService,
                new ObjectMapper()
        );

        AccountTransactionDto tx1 = AccountTransactionDto.builder()
                .transactionId("TXN-OFS-01")
                .sourceAccountId("ACC-100001")
                .targetAccountId("ACC-100002")
                .amount(new BigDecimal("1200.00"))
                .currency("PHP")
                .transactionType("TRANSFER")
                .status("Posted")
                .createdAt(Instant.now())
                .build();

        when(cbsService.getAccountTransactions("ACC-100001", 0, 20)).thenReturn(List.of(tx1));

        ResponseEntity<List<AccountTransactionDto>> response = controller.getAccountTransactions("ACC-100001", null, 0, 20);

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("TXN-OFS-01", response.getBody().get(0).getTransactionId());
        assertEquals(new BigDecimal("1200.00"), response.getBody().get(0).getAmount());
        verify(cbsService, times(1)).getAccountTransactions("ACC-100001", 0, 20);
    }

    @Test
    @DisplayName("GET /api/v1/transfers/accounts/{accountId}/transactions passes custom page and size parameters")
    void testGetAccountTransactionsWithPagination() {
        TransferOrchestratorController controller = new TransferOrchestratorController(
                orchestrationService,
                biometricService,
                coolOffService,
                cbsService,
                new ObjectMapper()
        );

        AccountTransactionDto tx2 = AccountTransactionDto.builder()
                .transactionId("TXN-OFS-02")
                .sourceAccountId("ACC-100001")
                .targetAccountId("ACC-100003")
                .amount(new BigDecimal("3500.00"))
                .currency("PHP")
                .transactionType("TRANSFER")
                .status("Posted")
                .createdAt(Instant.now())
                .build();

        when(cbsService.getAccountTransactions("ACC-100001", 2, 5)).thenReturn(List.of(tx2));

        ResponseEntity<List<AccountTransactionDto>> response = controller.getAccountTransactions("ACC-100001", null, 2, 5);

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("TXN-OFS-02", response.getBody().get(0).getTransactionId());
        verify(cbsService, times(1)).getAccountTransactions("ACC-100001", 2, 5);
    }

    @Test
    @DisplayName("GET /api/v1/transfers/{transactionId}/status-history routes to CBS client and returns status history")
    void testGetTransactionStatusHistoryEndpoint() {
        TransferOrchestratorController controller = new TransferOrchestratorController(
                orchestrationService,
                biometricService,
                coolOffService,
                cbsService,
                new ObjectMapper()
        );

        TransactionStatusHistoryDto item = TransactionStatusHistoryDto.builder()
                .historyId("HIST-99")
                .transactionId("TXN-STATUS-99")
                .fromStatus("INITIATED")
                .toStatus("POSTED")
                .changeReason("CBS_POSTING_CONFIRMED")
                .reasonDetails("Completed")
                .actorId("CORE")
                .actorType("SYSTEM")
                .changedAt(Instant.now())
                .build();

        when(cbsService.getTransactionStatusHistory("TXN-STATUS-99", 0, 20)).thenReturn(List.of(item));

        ResponseEntity<List<TransactionStatusHistoryDto>> response =
                controller.getTransactionStatusHistory("TXN-STATUS-99", 0, 20);

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("POSTED", response.getBody().get(0).getToStatus());
        assertEquals("CBS_POSTING_CONFIRMED", response.getBody().get(0).getChangeReason());
        verify(cbsService, times(1)).getTransactionStatusHistory("TXN-STATUS-99", 0, 20);
    }
}
