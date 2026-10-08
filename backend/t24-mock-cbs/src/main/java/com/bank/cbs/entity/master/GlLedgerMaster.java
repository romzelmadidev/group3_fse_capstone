package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "gl_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlLedgerMaster {

    @Id
    @Column(name = "journal_id", length = 64)
    private String journalId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "gl_code", nullable = false, length = 32)
    private String glCode;

    @Column(name = "debit_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal debitAmount;

    @Column(name = "credit_amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal creditAmount;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
