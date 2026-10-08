package com.bank.cbs.controller;

import com.bank.cbs.dto.BalanceEnquiryResponseDto;
import com.bank.cbs.entity.master.TransactionMaster;
import com.bank.cbs.service.CbsBalanceEnquiryService;
import com.bank.ledger.contracts.dto.AccountTransactionDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Dedicated Balance and Transaction Enquiry Controller.
 * Exposes canonical read endpoints returning pure Temenos OFS syntax:
 * 1. Balance Enquiry (GET /api/v1/cbs/accounts/{accountId}/balance)
 * 2. Transaction Enquiry (GET /api/v1/cbs/accounts/{accountId}/transactions)
 */
@RestController
@RequestMapping("/api/v1/cbs/accounts")
public class CbsBalanceController {

    private final CbsBalanceEnquiryService balanceEnquiryService;

    public CbsBalanceController(CbsBalanceEnquiryService balanceEnquiryService) {
        this.balanceEnquiryService = balanceEnquiryService;
    }

    @GetMapping(value = "/{accountId}/balance", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getBalanceById(@PathVariable String accountId) {
        BalanceEnquiryResponseDto dto = balanceEnquiryService.getBalanceByAccountId(accountId);
        String ofs = OfsMessageUtil.buildBalanceEnquiryResponse(
                dto.accountId(), dto.accountNumber(), dto.currentBalance(), dto.availableBalance(), dto.holdBalance(), dto.currency()
        );
        return ResponseEntity.ok(ofs);
    }

    @GetMapping(value = "/{accountId}/transactions", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getAccountTransactions(
            @PathVariable String accountId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        List<TransactionMaster> txList = balanceEnquiryService.getTransactionsByAccountId(accountId, page, size);
        List<AccountTransactionDto> dtos = txList.stream()
                .map(tx -> new AccountTransactionDto(
                        tx.getTransactionId(),
                        tx.getSourceAccountId(),
                        tx.getTargetAccountId(),
                        tx.getAmount(),
                        tx.getCurrency(),
                        tx.getTransactionType(),
                        tx.getStatus(),
                        tx.getMemo(),
                        tx.getCreatedAt()
                )).toList();
        String ofs = OfsMessageUtil.buildTransactionEnquiryResponse(accountId, dtos, page, size);
        return ResponseEntity.ok(ofs);
    }
}
