package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.ReversalRequestMaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReversalRequestMasterRepository extends JpaRepository<ReversalRequestMaster, String> {
    Optional<ReversalRequestMaster> findByOriginalTxId(String originalTxId);
    List<ReversalRequestMaster> findByStatus(String status);
    Page<ReversalRequestMaster> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<ReversalRequestMaster> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
