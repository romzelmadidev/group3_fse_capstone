package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.ComplianceFiling;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ComplianceFilingRepository extends JpaRepository<ComplianceFiling, String> {
    Optional<ComplianceFiling> findByTransactionId(String transactionId);
    List<ComplianceFiling> findByFilingTypeOrderByFiledAtDesc(String filingType);
}
