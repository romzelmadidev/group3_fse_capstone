package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.UserMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserMasterRepository extends JpaRepository<UserMaster, String> {
}
