package com.bank.cbs;

import com.bank.cbs.controller.CbsBalanceController;
import com.bank.cbs.controller.CbsPostingController;
import com.bank.cbs.controller.CbsReversalController;
import com.bank.cbs.entity.master.ReversalRequestMaster;
import com.bank.cbs.entity.master.TransactionMaster;
import com.bank.cbs.entity.master.TransactionStatusHistoryMaster;
import com.bank.cbs.repository.master.TransactionStatusHistoryMasterRepository;
import com.bank.cbs.service.CbsBalanceEnquiryService;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.dto.ReversalTicketDto;
import com.bank.ledger.contracts.dto.TransactionStatusHistoryDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsAccountTransactionEnquiryTest {

    @Mock
    private CbsBalanceEnquiryService balanceEnquiryService;

    @Mock
    private CbsFundsTransferService transferService;

    @Mock
    private CbsReversalService reversalService;

    @Mock
    private TransactionStatusHistoryMasterRepository statusHistoryRepository;

    @Test
    @DisplayName("Option B REST Endpoint: GET /api/v1/cbs/accounts/{accountId}/transactions returns past transactions")
    void testGetAccountTransactionsRest() {
        CbsBalanceController controller = new CbsBalanceController(balanceEnquiryService);

        TransactionMaster tx1 = TransactionMaster.builder()
                .transactionId("TXN-101")
                .sourceAccountId("ACC-100001")
                .targetAccountId("ACC-100002")
                .amount(new BigDecimal("1500.00"))
                .currency("PHP")
                .transactionType("INTRA_BANK")
                .status("Posted")
                .createdAt(Instant.parse("2026-10-07T10:00:00Z"))
                .build();

        TransactionMaster tx2 = TransactionMaster.builder()
                .transactionId("TXN-102")
                .sourceAccountId("ACC-100001")
                .targetAccountId("ACC-100003")
                .amount(new BigDecimal("2500.00"))
                .currency("PHP")
                .transactionType("INTRA_BANK")
                .status("Posted")
                .createdAt(Instant.parse("2026-10-07T14:00:00Z"))
                .build();

        when(balanceEnquiryService.getTransactionsByAccountId("ACC-100001", 0, 20)).thenReturn(List.of(tx2, tx1));

        ResponseEntity<String> response = controller.getAccountTransactions("ACC-100001", 0, 20);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().startsWith("//1,SUCCESS"));
        List<AccountTransactionDto> parsedList = OfsMessageUtil.parseTransactionEnquiryResponse(response.getBody());
        assertEquals(2, parsedList.size());
        assertEquals("TXN-102", parsedList.get(0).getTransactionId());
        assertEquals(new BigDecimal("2500.00"), parsedList.get(0).getAmount());
    }

    @Test
    @DisplayName("Option B REST Endpoint with pagination: page 1 size 1 returns single transaction")
    void testGetAccountTransactionsRestWithPagination() {
        CbsBalanceController controller = new CbsBalanceController(balanceEnquiryService);

        TransactionMaster tx2 = TransactionMaster.builder()
                .transactionId("TXN-102")
                .sourceAccountId("ACC-100001")
                .targetAccountId("ACC-100003")
                .amount(new BigDecimal("2500.00"))
                .currency("PHP")
                .transactionType("INTRA_BANK")
                .status("Posted")
                .createdAt(Instant.parse("2026-10-07T14:00:00Z"))
                .build();

        when(balanceEnquiryService.getTransactionsByAccountId("ACC-100001", 1, 1)).thenReturn(List.of(tx2));

        ResponseEntity<String> response = controller.getAccountTransactions("ACC-100001", 1, 1);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().startsWith("//1,SUCCESS"));
        List<AccountTransactionDto> parsedList = OfsMessageUtil.parseTransactionEnquiryResponse(response.getBody());
        assertEquals(1, parsedList.size());
        assertEquals("TXN-102", parsedList.get(0).getTransactionId());
    }

    @Test
    @DisplayName("OFS Enquiry: GET /api/v1/cbs/accounts/{accountId}/transactions formats pure OFS response")
    void testGetAccountTransactionsOfs() {
        CbsBalanceController controller = new CbsBalanceController(balanceEnquiryService);

        TransactionMaster tx1 = TransactionMaster.builder()
                .transactionId("TXN-201")
                .sourceAccountId("ACC-100001")
                .targetAccountId("ACC-100002")
                .amount(new BigDecimal("5000.00"))
                .currency("PHP")
                .transactionType("TRANSFER")
                .status("Posted")
                .createdAt(Instant.parse("2026-10-08T08:00:00Z"))
                .beforeBalance(new BigDecimal("15000.00"))
                .afterBalance(new BigDecimal("10000.00"))
                .build();

        when(balanceEnquiryService.getTransactionsByAccountId("ACC-100001", 0, 20)).thenReturn(List.of(tx1));

        ResponseEntity<String> response = controller.getAccountTransactions("ACC-100001", 0, 20);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().startsWith("//1,SUCCESS"), "Response should start with //1,SUCCESS but was: " + response.getBody());
        assertTrue(response.getBody().contains("ACCOUNT.NUMBER=ACC-100001"));

        List<AccountTransactionDto> parsed = OfsMessageUtil.parseTransactionEnquiryResponse(response.getBody());
        assertEquals(1, parsed.size());
        assertEquals("TXN-201", parsed.get(0).getTransactionId());
        assertEquals(new BigDecimal("5000.00"), parsed.get(0).getAmount());
        assertEquals(new BigDecimal("15000.00"), parsed.get(0).getBeforeBalance());
        assertEquals(new BigDecimal("10000.00"), parsed.get(0).getAfterBalance());
    }

    @Test
    @DisplayName("Transaction Status History OFS: GET /api/v1/cbs/transactions/{id}/status-history returns OFS status transitions")
    void testTransactionStatusHistoryOfs() {
        CbsPostingController controller = new CbsPostingController(transferService, reversalService, statusHistoryRepository);

        TransactionStatusHistoryMaster hist1 = TransactionStatusHistoryMaster.builder()
                .historyId("HIST-01")
                .transactionId("TXN-STATUS-01")
                .fromStatus("INITIATED")
                .toStatus("PROCESSING")
                .changeReason("ORCHESTRATOR_PICKUP")
                .reasonDetails("Dispatched to CBS queue")
                .actorId("ORCHESTRATOR")
                .actorType("SERVICE")
                .changedAt(Instant.parse("2026-10-09T08:00:00Z"))
                .build();

        TransactionStatusHistoryMaster hist2 = TransactionStatusHistoryMaster.builder()
                .historyId("HIST-02")
                .transactionId("TXN-STATUS-01")
                .fromStatus("PROCESSING")
                .toStatus("POSTED")
                .changeReason("CBS_POSTING_CONFIRMED")
                .reasonDetails("Ledger posted successfully")
                .actorId("CBS_POSTING_ENGINE")
                .actorType("SYSTEM")
                .changedAt(Instant.parse("2026-10-09T08:00:01Z"))
                .build();

        when(statusHistoryRepository.findByTransactionIdOrderByChangedAtAsc(eq("TXN-STATUS-01"), any()))
                .thenReturn(new PageImpl<>(List.of(hist1, hist2), PageRequest.of(0, 20), 2));

        ResponseEntity<String> response = controller.getTransactionStatusHistory("TXN-STATUS-01", 0, 20);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().startsWith("//1,SUCCESS"));
        List<TransactionStatusHistoryDto> historyList = OfsMessageUtil.parseStatusHistoryResponse(response.getBody());
        assertEquals(2, historyList.size());
        assertEquals("INITIATED", historyList.get(0).getFromStatus());
        assertEquals("PROCESSING", historyList.get(0).getToStatus());
        assertEquals("POSTED", historyList.get(1).getToStatus());
        assertEquals("CBS_POSTING_CONFIRMED", historyList.get(1).getChangeReason());
    }

    @Test
    @DisplayName("Reversals List OFS: GET /api/v1/cbs/reversals returns OFS formatted reversal requests")
    void testGetReversalRequestsOfs() {
        CbsReversalController controller = new CbsReversalController(reversalService);

        ReversalRequestMaster rev1 = ReversalRequestMaster.builder()
                .ticketId("REV-TKT-100")
                .originalTxId("TXN-ORIG-100")
                .makerId("MAKER01")
                .checkerId("CHECKER01")
                .status("PENDING")
                .disputeReason("DUPLICATE_CHARGE")
                .makerNotes("Customer reported double swipe")
                .createdAt(Instant.parse("2026-10-09T08:30:00Z"))
                .build();

        when(reversalService.getReversalRequests("PENDING", 0, 20)).thenReturn(List.of(rev1));

        ResponseEntity<String> response = controller.getReversalRequests("PENDING", 0, 20);

        assertNotNull(response.getBody());
        assertTrue(response.getBody().startsWith("//1,SUCCESS"));
        List<ReversalTicketDto> tickets = OfsMessageUtil.parseReversalListResponse(response.getBody());
        assertEquals(1, tickets.size());
        assertEquals("REV-TKT-100", tickets.get(0).getTicketId());
        assertEquals("TXN-ORIG-100", tickets.get(0).getOriginalTransactionId());
        assertEquals("DUPLICATE_CHARGE", tickets.get(0).getDisputeReason());
        assertEquals("PENDING", tickets.get(0).getStatus());
    }
}
