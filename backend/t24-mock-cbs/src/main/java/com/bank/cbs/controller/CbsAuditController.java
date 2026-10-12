package com.bank.cbs.controller;

import com.bank.cbs.entity.audit.ComplianceFiling;
import com.bank.cbs.entity.audit.EodReportsMetadata;
import com.bank.cbs.entity.audit.FailedTransactionAudit;
import com.bank.cbs.entity.audit.LedgerMutationAudit;
import com.bank.cbs.service.CbsAuditQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Dedicated Regulatory Compliance & Audit Controller.
 * Interacts exclusively with the PostgreSQL Immutable Audit Vault.
 * Exposes canonical JSON endpoints consumed by compliance-service and DLQ replay tools.
 */
@RestController
@RequestMapping("/api/v1/cbs/audit")
public class CbsAuditController {

    private final CbsAuditQueryService auditQueryService;

    public CbsAuditController(CbsAuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping({"", "/mutations", "/records"})
    public ResponseEntity<List<LedgerMutationAudit>> getAllLedgerMutations(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(auditQueryService.getAllLedgerMutations(page, size));
    }

    @GetMapping("/accounts/{accountId}/mutations")
    public ResponseEntity<List<LedgerMutationAudit>> getLedgerMutations(
            @PathVariable String accountId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(auditQueryService.getLedgerMutationsByAccount(accountId, page, size));
    }

    @GetMapping("/failed-transactions")
    public ResponseEntity<List<FailedTransactionAudit>> getFailedTransactions(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(auditQueryService.getUnresolvedFailedTransactions(page, size));
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

    @PostMapping("/compliance-filings")
    public ResponseEntity<ComplianceFiling> registerComplianceFiling(@RequestBody ComplianceFiling filing) {
        return ResponseEntity.ok(auditQueryService.registerComplianceFiling(filing));
    }
}
