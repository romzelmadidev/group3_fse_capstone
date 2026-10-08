package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.UncollectedFeeMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UncollectedFeeMasterRepository extends JpaRepository<UncollectedFeeMaster, String> {
    List<UncollectedFeeMaster> findByAccountId(String accountId);
    List<UncollectedFeeMaster> findByIsSettledFalse();
}
