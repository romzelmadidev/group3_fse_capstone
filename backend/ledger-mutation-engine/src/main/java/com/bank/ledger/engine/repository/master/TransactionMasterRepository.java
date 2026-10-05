package com.bank.ledger.engine.repository.master;

import com.bank.ledger.engine.entity.master.TransactionMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionMasterRepository extends JpaRepository<TransactionMaster, String> {
    List<TransactionMaster> findByStatus(String status);
    Optional<TransactionMaster> findTopByFromAccountIdOrderByCreatedAtDesc(String fromAccountId);
}