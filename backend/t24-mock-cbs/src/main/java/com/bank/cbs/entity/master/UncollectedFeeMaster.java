package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "uncollected_fees", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UncollectedFeeMaster {

    @Id
    @Column(name = "fee_id", length = 64)
    private String feeId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "fee_type", nullable = false, length = 50)
    private String feeType;

    @Column(name = "amount_due", nullable = false, precision = 18, scale = 4)
    private BigDecimal amountDue;

    @Column(name = "amount_collected", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal amountCollected = BigDecimal.ZERO;

    @Column(name = "is_settled", nullable = false)
    @Builder.Default
    private Boolean isSettled = false;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
