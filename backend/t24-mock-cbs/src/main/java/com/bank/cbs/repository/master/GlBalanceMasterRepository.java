package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.GlBalanceId;
import com.bank.cbs.entity.master.GlBalanceMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GlBalanceMasterRepository extends JpaRepository<GlBalanceMaster, GlBalanceId> {
    List<GlBalanceMaster> findByFiscalPeriod(String fiscalPeriod);
    Optional<GlBalanceMaster> findByGlCodeAndFiscalPeriod(String glCode, String fiscalPeriod);
    Optional<GlBalanceMaster> findFirstByGlCodeOrderByFiscalPeriodDesc(String glCode);
}
