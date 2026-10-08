package com.bank.cbs.repository.master;

import com.bank.cbs.entity.master.OutboxEventMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventMasterRepository extends JpaRepository<OutboxEventMaster, String> {
    List<OutboxEventMaster> findByStatusOrderByCreatedAtAsc(String status);
}
