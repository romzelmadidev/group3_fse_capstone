package com.bank.cbs.entity.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "eod_reports_metadata")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EodReportsMetadata {

    @Id
    @Column(name = "report_id", length = 64)
    private String reportId;

    @Column(name = "report_type", nullable = false, length = 50)
    private String reportType; // GL_TRIAL_BALANCE_EXCEL, BIR_FORM_2306_WITHHOLDING_PDF, AMLA_CTR_XML_BATCH, CUSTOMER_STATEMENT_PDF

    @Column(name = "business_date", nullable = false, length = 20)
    private String businessDate;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "storage_uri", nullable = false, length = 500)
    private String storageUri;

    @Column(name = "sha256_checksum", nullable = false, length = 64)
    private String sha256Checksum;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "record_count", nullable = false)
    @Builder.Default
    private Long recordCount = 0L;

    @Column(name = "generated_at_utc", nullable = false)
    @Builder.Default
    private Instant generatedAtUtc = Instant.now();

    @Column(name = "retention_expiry_date", nullable = false)
    private Instant retentionExpiryDate;
}
