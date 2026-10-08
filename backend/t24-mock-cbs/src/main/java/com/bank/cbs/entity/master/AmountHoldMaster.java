package com.bank.cbs.entity.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "amount_holds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AmountHoldMaster {

    @Id
    @Column(name = "hold_id", length = 64)
    private String holdId;

    @Column(name = "account_id", length = 64, nullable = false)
    private String accountId;

    @Column(name = "hold_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal holdAmount;

    @Column(name = "reason", length = 100, nullable = false)
    private String reason;

    @Column(name = "status", length = 20, nullable = false)
    private String status; // ACTIVE, RELEASED, CAPTURED

    @Column(name = "t24_lock_reference", length = 64)
    private String t24LockReference;

    @Column(name = "external_reference", length = 100)
    private String externalReference;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;
}
