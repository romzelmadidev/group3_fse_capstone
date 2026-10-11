package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.CobBatchLogMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CobBatchLogMasterRepository extends JpaRepository<CobBatchLogMaster, String> {
    List<CobBatchLogMaster> findByBusinessDateOrderByStartedAtAsc(LocalDate businessDate);
}
