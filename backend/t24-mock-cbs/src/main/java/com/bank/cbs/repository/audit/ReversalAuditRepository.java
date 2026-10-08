package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.ReversalAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReversalAuditRepository extends JpaRepository<ReversalAudit, Long> {
    Optional<ReversalAudit> findByOriginalTxId(String originalTxId);
    Optional<ReversalAudit> findByReversalTxId(String reversalTxId);
    Optional<ReversalAudit> findTopByOrderByAuditIdDesc();
}
