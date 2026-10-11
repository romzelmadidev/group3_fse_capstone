package com.bank.cbs.entity.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions", schema = "core")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionMaster {

    @Id
    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "from_account_id", length = 64)
    private String sourceAccountId;

    @Column(name = "to_account_id", length = 64)
    private String targetAccountId;

    public String getFromAccountId() {
        return sourceAccountId;
    }

    public void setFromAccountId(String fromAccountId) {
        this.sourceAccountId = fromAccountId;
    }

    public String getToAccountId() {
        return targetAccountId;
    }

    public void setToAccountId(String toAccountId) {
        this.targetAccountId = toAccountId;
    }

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "before_balance", precision = 18, scale = 4)
    private BigDecimal beforeBalance;

    @Column(name = "after_balance", precision = 18, scale = 4)
    private BigDecimal afterBalance;

    @Column(name = "currency", length = 3)
    @Builder.Default
    private String currency = "PHP";

    @Column(name = "type", length = 30)
    @Builder.Default
    private String type = "TRANSFER";

    @Column(name = "transaction_type", length = 20)
    @Builder.Default
    private String transactionType = "INTRA_BANK";

    @Column(name = "status", nullable = false, length = 30)
    private String status; // Initiated, Authorized, Reserved, Processing, Posted, Failed, Cancelled, PendingReversal, Reversed

    @Column(name = "requires_maker_checker")
    @Builder.Default
    private Integer requiresMakerChecker = 0;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Column(name = "memo", length = 255)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
