package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.TransactionMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionMasterRepository extends JpaRepository<TransactionMaster, String> {
    List<TransactionMaster> findBySourceAccountIdOrTargetAccountId(String sourceAccountId, String targetAccountId);
}
