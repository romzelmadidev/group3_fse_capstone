package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.ParticipantBankMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ParticipantBankRepository extends JpaRepository<ParticipantBankMaster, String> {
    Optional<ParticipantBankMaster> findByBankCodeIgnoreCase(String bankCode);
    List<ParticipantBankMaster> findAllByIsActive(Integer isActive);
}
