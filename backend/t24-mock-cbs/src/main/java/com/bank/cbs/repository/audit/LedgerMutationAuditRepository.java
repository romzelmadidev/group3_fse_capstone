package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.LedgerMutationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerMutationAuditRepository extends JpaRepository<LedgerMutationAudit, Long> {
    List<LedgerMutationAudit> findByAccountIdOrderByCreatedAtAsc(String accountId);
    Optional<LedgerMutationAudit> findByTransactionId(String transactionId);
    Optional<LedgerMutationAudit> findTopByOrderByAuditIdDesc();
}
