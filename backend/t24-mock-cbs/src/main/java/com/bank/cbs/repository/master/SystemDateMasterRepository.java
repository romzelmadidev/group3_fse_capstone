package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.SystemDateMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemDateMasterRepository extends JpaRepository<SystemDateMaster, String> {
    Optional<SystemDateMaster> findTopByOrderBySystemDateIdAsc();
}
