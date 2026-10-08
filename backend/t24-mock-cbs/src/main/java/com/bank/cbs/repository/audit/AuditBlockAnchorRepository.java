package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.AuditBlockAnchor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditBlockAnchorRepository extends JpaRepository<AuditBlockAnchor, Long> {
    Optional<AuditBlockAnchor> findTopByOrderByBlockNumberDesc();
    Optional<AuditBlockAnchor> findByBlockNumber(Long blockNumber);
    List<AuditBlockAnchor> findAllByOrderByBlockNumberAsc();
    Optional<AuditBlockAnchor> findFirstByStartAuditIdLessThanEqualAndEndAuditIdGreaterThanEqual(Long auditId1, Long auditId2);
}
