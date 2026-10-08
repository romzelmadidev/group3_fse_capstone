package com.bank.cbs.controller;

import com.bank.cbs.entity.audit.*;
import com.bank.cbs.service.CbsAuditQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cbs/audit")
public class CbsAuditController {

    private final CbsAuditQueryService auditQueryService;

    public CbsAuditController(CbsAuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/accounts/{accountId}/mutations")
    public ResponseEntity<List<LedgerMutationAudit>> getLedgerMutations(@PathVariable String accountId) {
        return ResponseEntity.ok(auditQueryService.getLedgerMutationsByAccount(accountId));
    }

    @GetMapping("/transactions/{transactionId}/status-history")
    public ResponseEntity<List<TransactionStatusAudit>> getStatusHistory(@PathVariable String transactionId) {
        return ResponseEntity.ok(auditQueryService.getStatusHistory(transactionId));
    }

    @GetMapping("/reversals/{transactionId}")
    public ResponseEntity<ReversalAudit> getReversalAudit(@PathVariable String transactionId) {
        return auditQueryService.getReversalByOriginalTx(transactionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/failed-transactions")
    public ResponseEntity<List<FailedTransactionAudit>> getFailedTransactions() {
        return ResponseEntity.ok(auditQueryService.getUnresolvedFailedTransactions());
    }

    @PostMapping("/failed-transactions/{transferId}/resolve")
    public ResponseEntity<FailedTransactionAudit> resolveFailedTransaction(
            @PathVariable String transferId,
            @RequestBody Map<String, String> request) {
        String notes = request.getOrDefault("notes", "Resolved manually by compliance operator");
        String operator = request.getOrDefault("operatorId", "COMPLIANCE_OPERATOR");
        return ResponseEntity.ok(auditQueryService.resolveFailedTransaction(transferId, notes, operator));
    }

    @GetMapping("/eod-reports")
    public ResponseEntity<List<EodReportsMetadata>> getEodReports(@RequestParam("eodDate") String eodDate) {
        return ResponseEntity.ok(auditQueryService.getEodReports(eodDate));
    }

    @PostMapping("/eod-reports")
    public ResponseEntity<EodReportsMetadata> registerEodReport(@RequestBody EodReportsMetadata metadata) {
        return ResponseEntity.ok(auditQueryService.registerEodReportMetadata(metadata));
    }

    @GetMapping("/compliance-filings")
    public ResponseEntity<List<ComplianceFiling>> getComplianceFilings(@RequestParam("type") String filingType) {
        return ResponseEntity.ok(auditQueryService.getComplianceFilings(filingType));
    }

    @PostMapping("/compliance-filings")
    public ResponseEntity<ComplianceFiling> registerComplianceFiling(@RequestBody ComplianceFiling filing) {
        return ResponseEntity.ok(auditQueryService.registerComplianceFiling(filing));
    }
}
