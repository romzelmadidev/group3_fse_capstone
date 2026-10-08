package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "interest_accruals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterestAccrualMaster {

    @Id
    @Column(name = "accrual_id", length = 64)
    private String accrualId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "accrual_date", nullable = false)
    private LocalDate accrualDate;

    @Column(name = "daily_rate", nullable = false, precision = 12, scale = 8)
    private BigDecimal dailyRate;

    @Column(name = "accrued_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal accruedAmount;

    @Column(name = "tax_withheld", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal taxWithheld = BigDecimal.ZERO;

    @Column(name = "net_accrual", nullable = false, precision = 18, scale = 4)
    private BigDecimal netAccrual;

    @Column(name = "is_capitalized", nullable = false)
    @Builder.Default
    private Boolean isCapitalized = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
