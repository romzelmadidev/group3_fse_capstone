package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.FailedTransactionAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FailedTransactionAuditRepository extends JpaRepository<FailedTransactionAudit, String> {
    Optional<FailedTransactionAudit> findByTransactionId(String transactionId);
    List<FailedTransactionAudit> findByReplayStatus(String replayStatus);
    List<FailedTransactionAudit> findByReplayStatusOrderByFailureTimestampDesc(String replayStatus, Pageable pageable);
}
