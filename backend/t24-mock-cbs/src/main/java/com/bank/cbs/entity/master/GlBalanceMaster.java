package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "gl_balances", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlBalanceMaster {

    @Id
    @Column(name = "gl_code", length = 32)
    private String glCode;

    @Column(name = "fiscal_period", nullable = false, length = 20)
    private String fiscalPeriod;

    @Column(name = "total_debit", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalDebit = BigDecimal.ZERO;

    @Column(name = "total_credit", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalCredit = BigDecimal.ZERO;

    @Column(name = "net_balance", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal netBalance = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
