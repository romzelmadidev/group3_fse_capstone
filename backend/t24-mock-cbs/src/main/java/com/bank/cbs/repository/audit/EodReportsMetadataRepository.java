package com.bank.cbs.repository.audit;

import com.bank.cbs.entity.audit.EodReportsMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EodReportsMetadataRepository extends JpaRepository<EodReportsMetadata, String> {
    List<EodReportsMetadata> findByBusinessDateOrderByGeneratedAtUtcDesc(String businessDate);
    Optional<EodReportsMetadata> findByReportTypeAndBusinessDate(String reportType, String businessDate);
}
