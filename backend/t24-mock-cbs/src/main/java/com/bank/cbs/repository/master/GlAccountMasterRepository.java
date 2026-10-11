package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.GlAccountMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GlAccountMasterRepository extends JpaRepository<GlAccountMaster, String> {
    List<GlAccountMaster> findByIsActiveTrue();
}
