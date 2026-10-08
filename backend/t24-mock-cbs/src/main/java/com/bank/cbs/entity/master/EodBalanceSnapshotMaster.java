package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "eod_balance_snapshots", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EodBalanceSnapshotMaster {

    @Id
    @Column(name = "snapshot_id", length = 64)
    private String snapshotId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "closing_balance", nullable = false, precision = 18, scale = 4)
    private BigDecimal closingBalance;

    @Column(name = "frozen_at", nullable = false)
    @Builder.Default
    private Instant frozenAt = Instant.now();
}
