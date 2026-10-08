package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.AmountHoldMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AmountHoldMasterRepository extends JpaRepository<AmountHoldMaster, String> {

    List<AmountHoldMaster> findByAccountIdOrderByCreatedAtDesc(String accountId);

    List<AmountHoldMaster> findByAccountIdAndStatus(String accountId, String status);

    Optional<AmountHoldMaster> findByHoldIdAndStatus(String holdId, String status);
}
