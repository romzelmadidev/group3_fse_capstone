package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.LedgerMutationAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerMutationAuditRepository extends JpaRepository<LedgerMutationAudit, Long> {
    List<LedgerMutationAudit> findByAccountIdOrderByCreatedAtAsc(String accountId);
    List<LedgerMutationAudit> findByAccountIdOrderByCreatedAtAsc(String accountId, Pageable pageable);
    Optional<LedgerMutationAudit> findByTransactionId(String transactionId);
    Optional<LedgerMutationAudit> findFirstByTransactionId(String transactionId);
    Optional<LedgerMutationAudit> findByTransactionIdAndMutationType(String transactionId, String mutationType);
    List<LedgerMutationAudit> findAllByTransactionId(String transactionId);
    Optional<LedgerMutationAudit> findTopByOrderByAuditIdDesc();
    List<LedgerMutationAudit> findByAuditIdBetweenOrderByAuditIdAsc(Long startAuditId, Long endAuditId);
    List<LedgerMutationAudit> findByAuditIdGreaterThanOrderByAuditIdAsc(Long lastAuditId);
    List<LedgerMutationAudit> findAllByOrderByAuditIdAsc();
}
