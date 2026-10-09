package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.TransactionStatusHistoryMaster;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionStatusHistoryMasterRepository extends JpaRepository<TransactionStatusHistoryMaster, String> {
    List<TransactionStatusHistoryMaster> findByTransactionIdOrderByChangedAtAsc(String transactionId);
    Page<TransactionStatusHistoryMaster> findByTransactionIdOrderByChangedAtAsc(String transactionId, Pageable pageable);
}
