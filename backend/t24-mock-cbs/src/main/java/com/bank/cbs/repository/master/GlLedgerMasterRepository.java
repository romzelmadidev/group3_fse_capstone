package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.GlLedgerMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface GlLedgerMasterRepository extends JpaRepository<GlLedgerMaster, String> {

    List<GlLedgerMaster> findByTransactionId(String transactionId);

    List<GlLedgerMaster> findByPostingDate(LocalDate postingDate);

    @Query("SELECT COALESCE(SUM(g.debitAmount), 0) FROM GlLedgerMaster g")
    BigDecimal sumTotalDebits();

    @Query("SELECT COALESCE(SUM(g.creditAmount), 0) FROM GlLedgerMaster g")
    BigDecimal sumTotalCredits();

    @Query("SELECT COALESCE(SUM(g.debitAmount), 0) FROM GlLedgerMaster g WHERE g.postingDate = :postingDate")
    BigDecimal sumTotalDebitsForDate(@Param("postingDate") LocalDate postingDate);

    @Query("SELECT COALESCE(SUM(g.creditAmount), 0) FROM GlLedgerMaster g WHERE g.postingDate = :postingDate")
    BigDecimal sumTotalCreditsForDate(@Param("postingDate") LocalDate postingDate);
}
