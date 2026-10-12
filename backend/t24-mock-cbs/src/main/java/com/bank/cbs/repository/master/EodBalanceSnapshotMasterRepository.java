package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.EodBalanceSnapshotMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EodBalanceSnapshotMasterRepository extends JpaRepository<EodBalanceSnapshotMaster, String> {
    List<EodBalanceSnapshotMaster> findByBusinessDate(LocalDate businessDate);
    Optional<EodBalanceSnapshotMaster> findByAccountIdAndBusinessDate(String accountId, LocalDate businessDate);
    List<EodBalanceSnapshotMaster> findByAccountIdAndBusinessDateBetween(String accountId, LocalDate startDate, LocalDate endDate);
}
