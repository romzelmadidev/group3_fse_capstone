package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "cob_batch_log", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CobBatchLogMaster {

    @Id
    @Column(name = "batch_id", length = 64)
    private String batchId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "RUNNING"; // RUNNING, COMPLETED, FAILED

    @Column(name = "current_phase", length = 50)
    private String currentPhase;

    @Column(name = "accounts_processed", nullable = false)
    @Builder.Default
    private Integer accountsProcessed = 0;

    @Column(name = "total_fees_collected", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalFeesCollected = BigDecimal.ZERO;

    @Column(name = "total_interest_accrued", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalInterestAccrued = BigDecimal.ZERO;

    @Column(name = "total_tax_withheld", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalTaxWithheld = BigDecimal.ZERO;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
