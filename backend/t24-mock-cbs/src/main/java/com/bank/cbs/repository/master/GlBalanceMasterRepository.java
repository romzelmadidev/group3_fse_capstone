package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.GlBalanceMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GlBalanceMasterRepository extends JpaRepository<GlBalanceMaster, String> {
    List<GlBalanceMaster> findByFiscalPeriod(String fiscalPeriod);
}
