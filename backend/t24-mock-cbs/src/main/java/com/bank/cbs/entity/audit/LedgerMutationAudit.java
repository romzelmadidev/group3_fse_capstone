package com.bank.cbs.entity.audit;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ledger_mutation_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerMutationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "mutation_type", nullable = false, length = 16)
    private String mutationType; // TRANSFER, DEBIT, CREDIT, HOLD, RELEASE, REVERSAL

    @Column(name = "mutation_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal mutationAmount;

    @Column(name = "before_balance", nullable = false, precision = 18, scale = 4)
    private BigDecimal beforeBalance;

    @Column(name = "after_balance", nullable = false, precision = 18, scale = 4)
    private BigDecimal afterBalance;

    @Column(name = "initiator_user_id", nullable = false, length = 64)
    private String initiatorUserId;

    @Column(name = "approved_by_user_id", length = 64)
    private String approvedByUserId;

    @Column(name = "status", nullable = false, length = 20)
    private String status; // COMMITTED, FAILED, ROLLED_BACK

    @Column(name = "prev_hash", length = 64)
    private String prevHash;

    @Column(name = "sha256_hash", length = 64)
    private String sha256Hash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
