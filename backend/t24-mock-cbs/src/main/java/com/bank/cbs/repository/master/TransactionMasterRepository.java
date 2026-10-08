package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.TransactionMaster;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionMasterRepository extends JpaRepository<TransactionMaster, String> {
    List<TransactionMaster> findBySourceAccountIdOrTargetAccountId(String sourceAccountId, String targetAccountId);
    List<TransactionMaster> findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(String sourceAccountId, String targetAccountId);
    List<TransactionMaster> findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(String sourceAccountId, String targetAccountId, Pageable pageable);
    Optional<TransactionMaster> findByIdempotencyKey(String idempotencyKey);
}
