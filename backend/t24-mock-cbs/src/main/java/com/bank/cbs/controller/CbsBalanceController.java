package com.bank.cbs.controller;

import com.bank.cbs.dto.BalanceEnquiryResponseDto;
import com.bank.cbs.entity.master.TransactionMaster;
import com.bank.cbs.service.CbsBalanceEnquiryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cbs/accounts")
public class CbsBalanceController {

    private final CbsBalanceEnquiryService balanceEnquiryService;

    public CbsBalanceController(CbsBalanceEnquiryService balanceEnquiryService) {
        this.balanceEnquiryService = balanceEnquiryService;
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<BalanceEnquiryResponseDto> getBalanceById(@PathVariable String accountId) {
        return ResponseEntity.ok(balanceEnquiryService.getBalanceByAccountId(accountId));
    }

    @GetMapping("/by-number/{accountNumber}/balance")
    public ResponseEntity<BalanceEnquiryResponseDto> getBalanceByNumber(@PathVariable String accountNumber) {
        return ResponseEntity.ok(balanceEnquiryService.getBalanceByAccountNumber(accountNumber));
    }

    @GetMapping("/{accountId}/transactions")
    public ResponseEntity<List<TransactionMaster>> getAccountTransactions(
            @PathVariable String accountId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(balanceEnquiryService.getTransactionsByAccountId(accountId, page, size));
    }
}
