package com.bank.ledger.engine.repository.audit;

import com.bank.ledger.engine.entity.audit.LedgerMutationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface LedgerMutationAuditRepository extends JpaRepository<LedgerMutationAudit, Long> {
    Optional<LedgerMutationAudit> findByTransactionId(String transactionId);
    Optional<LedgerMutationAudit> findFirstByTransactionId(String transactionId);
    Optional<LedgerMutationAudit> findByTransactionIdAndMutationType(String transactionId, String mutationType);
    java.util.List<LedgerMutationAudit> findAllByTransactionId(String transactionId);
    java.util.List<LedgerMutationAudit> findAllByOrderByAuditIdDesc();
}