package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.BalanceMaster;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BalanceMasterRepository extends JpaRepository<BalanceMaster, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BalanceMaster b WHERE b.accountId = :accountId")
    Optional<BalanceMaster> findByAccountIdForUpdate(@Param("accountId") String accountId);
}
