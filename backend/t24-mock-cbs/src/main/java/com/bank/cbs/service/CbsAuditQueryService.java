package com.bank.cbs.service;

import com.bank.cbs.dto.*;
import com.bank.cbs.entity.audit.*;
import com.bank.cbs.repository.audit.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CbsAuditQueryService {

    private final LedgerMutationAuditRepository ledgerMutationAuditRepository;
    private final TransactionStatusAuditRepository transactionStatusAuditRepository;
    private final FailedTransactionAuditRepository failedTransactionAuditRepository;
    private final EodReportsMetadataRepository eodReportsMetadataRepository;
    private final ComplianceFilingRepository complianceFilingRepository;
    private final CbsAuditAnchoringService auditAnchoringService;

    public CbsAuditQueryService(
            LedgerMutationAuditRepository ledgerMutationAuditRepository,
            TransactionStatusAuditRepository transactionStatusAuditRepository,
            FailedTransactionAuditRepository failedTransactionAuditRepository,
            EodReportsMetadataRepository eodReportsMetadataRepository,
            ComplianceFilingRepository complianceFilingRepository,
            CbsAuditAnchoringService auditAnchoringService) {
        this.ledgerMutationAuditRepository = ledgerMutationAuditRepository;
        this.transactionStatusAuditRepository = transactionStatusAuditRepository;
        this.failedTransactionAuditRepository = failedTransactionAuditRepository;
        this.eodReportsMetadataRepository = eodReportsMetadataRepository;
        this.complianceFilingRepository = complianceFilingRepository;
        this.auditAnchoringService = auditAnchoringService;
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<LedgerMutationAudit> getLedgerMutationsByAccount(String accountId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return ledgerMutationAuditRepository.findByAccountIdOrderByCreatedAtAsc(
                accountId, PageRequest.of(safePage, safeSize));
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<LedgerMutationAudit> getLedgerMutationsByAccount(String accountId) {
        return getLedgerMutationsByAccount(accountId, 0, 50);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<TransactionStatusAudit> getStatusHistory(String transactionId) {
        return transactionStatusAuditRepository.findByTransactionIdOrderByChangedAtAsc(transactionId);
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<LedgerMutationAudit> getReversalByOriginalTx(String originalTransactionId) {
        return ledgerMutationAuditRepository.findAllByTransactionId(originalTransactionId).stream()
                .filter(a -> "REVERSAL".equals(a.getMutationType()))
                .toList();
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<FailedTransactionAudit> getUnresolvedFailedTransactions(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        return failedTransactionAuditRepository.findByReplayStatusOrderByFailureTimestampDesc(
                "PENDING_REPLAY", PageRequest.of(safePage, safeSize));
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<FailedTransactionAudit> getUnresolvedFailedTransactions() {
        return getUnresolvedFailedTransactions(0, 20);
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

    @Transactional("auditTransactionManager")
    public FailedTransactionAudit createFailedTransactionAudit(
            String transactionId,
            String errorType,
            String errorCode,
            String circuitBreakerState,
            String payloadJson,
            String stackTrace) {
        String incidentId = "INC-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String txId = (transactionId != null && !transactionId.isBlank()) 
                ? transactionId 
                : ("FAIL-TX-" + java.util.UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        FailedTransactionAudit audit = FailedTransactionAudit.builder()
                .incidentId(incidentId)
                .correlationId("CORR-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .transactionId(txId)
                .errorType(errorType != null ? errorType : "NETWORK_TIMEOUT")
                .errorCode(errorCode != null ? errorCode : "HTTP_504")
                .circuitBreakerState(circuitBreakerState != null ? circuitBreakerState : "OPEN")
                .payloadJson(payloadJson != null ? payloadJson : "{\"amount\":5000.00,\"sourceAccountId\":\"ACC-1001\",\"destinationAccountId\":\"ACC-1002\"}")
                .stackTrace(stackTrace != null ? stackTrace : "Simulated downstream CBS timeout after 3 retries. Routed to DLQ.")
                .replayStatus("PENDING_REPLAY")
                .failureTimestamp(Instant.now())
                .build();
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

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public List<AuditBlockAnchor> getAllAuditBlocks() {
        return auditAnchoringService.getAllBlocks();
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public Optional<AuditBlockAnchor> getAuditBlockByNumber(Long blockNumber) {
        return auditAnchoringService.getBlockByNumber(blockNumber);
    }

    @Transactional("auditTransactionManager")
    public Optional<AuditBlockAnchor> anchorCurrentBlock() {
        return auditAnchoringService.anchorCurrentBlock();
    }

    @Transactional(value = "auditTransactionManager", readOnly = true)
    public MerkleProofDto getMerkleProof(String transactionId) {
        return auditAnchoringService.generateProofForTransaction(transactionId);
    }

    public MerkleVerificationResponseDto verifyMerkleProof(MerkleVerificationRequestDto request) {
        return auditAnchoringService.verifyProof(request);
    }
}
