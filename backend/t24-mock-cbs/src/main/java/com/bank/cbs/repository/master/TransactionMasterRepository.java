package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.TransactionMaster;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface TransactionMasterRepository extends JpaRepository<TransactionMaster, String> {
    /** Upper-case statuses that count toward a customer's outgoing transfer limit usage. */
    Set<String> COUNTED_TRANSFER_STATUSES = Set.of(
            "INITIATED", "AUTHORIZED", "RESERVED", "PROCESSING", "POSTED", "PENDINGREVERSAL",
            "PENDING_APPROVAL", "COMMITTED");

    @Query("select coalesce(sum(t.amount), 0) from TransactionMaster t " +
            "where t.sourceAccountId in :ids and t.type = 'TRANSFER' and upper(t.status) in :statuses " +
            "and t.createdAt >= :from and t.createdAt < :to")
    BigDecimal sumOutgoing(@Param("ids") Collection<String> ids,
                           @Param("statuses") Collection<String> statuses,
                           @Param("from") Instant from,
                           @Param("to") Instant to);

    List<TransactionMaster> findBySourceAccountIdOrTargetAccountId(String sourceAccountId, String targetAccountId);
    List<TransactionMaster> findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(String sourceAccountId, String targetAccountId);
    List<TransactionMaster> findBySourceAccountIdOrTargetAccountIdOrderByCreatedAtDesc(String sourceAccountId, String targetAccountId, Pageable pageable);
    Optional<TransactionMaster> findByIdempotencyKey(String idempotencyKey);
}
