package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.AccountMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountMasterRepository extends JpaRepository<AccountMaster, String> {
    Optional<AccountMaster> findByAccountNumber(String accountNumber);
}
