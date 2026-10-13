package com.bank.cbs.controller;

import com.bank.cbs.repository.master.TransactionMasterRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outgoing transfer usage for transfer limit enforcement.
 * Internal JSON endpoint (GET /api/v1/cbs/usage/outgoing); sums counted outgoing
 * transfers for a set of accounts over a day window and a month window.
 */
@RestController
@RequestMapping("/api/v1/cbs/usage")
public class CbsUsageController {

    private final TransactionMasterRepository transactionRepository;

    public CbsUsageController(TransactionMasterRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @GetMapping(value = "/outgoing", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, BigDecimal>> getOutgoingUsage(
            @RequestParam("accountIds") List<String> accountIds,
            @RequestParam("dayStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dayStart,
            @RequestParam("dayEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dayEnd,
            @RequestParam("monthStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant monthStart,
            @RequestParam("monthEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant monthEnd) {
        BigDecimal daily = sum(accountIds, dayStart, dayEnd);
        BigDecimal monthly = sum(accountIds, monthStart, monthEnd);

        Map<String, BigDecimal> body = new LinkedHashMap<>();
        body.put("daily_used", daily);
        body.put("monthly_used", monthly);
        return ResponseEntity.ok(body);
    }

    private BigDecimal sum(List<String> accountIds, Instant from, Instant to) {
        if (accountIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return transactionRepository.sumOutgoing(
                accountIds, TransactionMasterRepository.COUNTED_TRANSFER_STATUSES, from, to);
    }
}
