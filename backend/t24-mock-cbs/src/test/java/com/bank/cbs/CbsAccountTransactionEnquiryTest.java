package com.bank.cbs;

import com.bank.cbs.controller.CbsBalanceController;
import com.bank.cbs.controller.CbsPostingController;
import com.bank.cbs.entity.master.TransactionMaster;
import com.bank.cbs.service.CbsBalanceEnquiryService;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    }
}
