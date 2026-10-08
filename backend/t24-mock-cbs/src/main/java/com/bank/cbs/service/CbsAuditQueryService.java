package com.bank.cbs.service;

import com.bank.cbs.entity.audit.*;
import com.bank.cbs.repository.audit.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CbsAuditQueryService {

    private final LedgerMutationAuditRepository ledgerMutationAuditRepository;
    private final ReversalAuditRepository reversalAuditRepository;
    private final TransactionStatusAuditRepository transactionStatusAuditRepository;
    private final FailedTransactionAuditRepository failedTransactionAuditRepository;
    private final EodReportsMetadataRepository eodReportsMetadataRepository;
    private final ComplianceFilingRepository complianceFilingRepository;

    public CbsAuditQueryService(
            LedgerMutationAuditRepository ledgerMutationAuditRepository,
            ReversalAuditRepository reversalAuditRepository,
            TransactionStatusAuditRepository transactionStatusAuditRepository,
            FailedTransactionAuditRepository failedTransactionAuditRepository,
            EodReportsMetadataRepository eodReportsMetadataRepository,
            ComplianceFilingRepository complianceFilingRepository) {
        this.ledgerMutationAuditRepository = ledgerMutationAuditRepository;
        this.reversalAuditRepository = reversalAuditRepository;
        this.transactionStatusAuditRepository = transactionStatusAuditRepository;
        this.failedTransactionAuditRepository = failedTransactionAuditRepository;
        this.eodReportsMetadataRepository = eodReportsMetadataRepository;
        this.complianceFilingRepository = complianceFilingRepository;
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<LedgerMutationAudit> getLedgerMutationsByAccount(String accountId) {
        return ledgerMutationAuditRepository.findByAccountIdOrderByCreatedAtAsc(accountId);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<TransactionStatusAudit> getStatusHistory(String transactionId) {
        return transactionStatusAuditRepository.findByTransactionIdOrderByChangedAtAsc(transactionId);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public Optional<ReversalAudit> getReversalByOriginalTx(String originalTransactionId) {
        return reversalAuditRepository.findByOriginalTxId(originalTransactionId);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<FailedTransactionAudit> getUnresolvedFailedTransactions() {
        return failedTransactionAuditRepository.findByReplayStatus("PENDING_REPLAY");
    }

    @Transactional("auditTransactionManager")
    public FailedTransactionAudit resolveFailedTransaction(String transactionId, String resolutionNotes, String operatorId) {
        FailedTransactionAudit audit = failedTransactionAuditRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Failed transaction record not found: " + transactionId));
        audit.setReplayStatus("RESOLVED");
        audit.setResolvedBy(operatorId != null ? operatorId : "SYSTEM");
        audit.setResolvedAt(Instant.now());
        return failedTransactionAuditRepository.save(audit);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<EodReportsMetadata> getEodReports(String businessDate) {
        return eodReportsMetadataRepository.findByBusinessDateOrderByGeneratedAtUtcDesc(businessDate);
    }

    @Transactional("auditTransactionManager")
    public EodReportsMetadata registerEodReportMetadata(EodReportsMetadata metadata) {
        return eodReportsMetadataRepository.save(metadata);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<ComplianceFiling> getComplianceFilings(String filingType) {
        return complianceFilingRepository.findByFilingTypeOrderByFiledAtDesc(filingType);
    }

    @Transactional("auditTransactionManager")
    public ComplianceFiling registerComplianceFiling(ComplianceFiling filing) {
        return complianceFilingRepository.save(filing);
    }
}
