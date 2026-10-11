package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.InterestAccrualMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface InterestAccrualMasterRepository extends JpaRepository<InterestAccrualMaster, String> {
    List<InterestAccrualMaster> findByAccountId(String accountId);
    List<InterestAccrualMaster> findByAccrualDate(LocalDate accrualDate);
}
