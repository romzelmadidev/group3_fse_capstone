package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.TransactionStatusAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionStatusAuditRepository extends JpaRepository<TransactionStatusAudit, Long> {
    List<TransactionStatusAudit> findByTransactionIdOrderByChangedAtAsc(String transactionId);
    Optional<TransactionStatusAudit> findTopByOrderByAuditIdDesc();
}
