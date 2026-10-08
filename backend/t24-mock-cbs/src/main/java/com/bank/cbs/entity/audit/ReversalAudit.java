package com.bank.cbs.entity.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "reversal_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReversalAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id")
    private Long auditId;

    @Column(name = "ticket_id", nullable = false, unique = true, length = 64)
    private String ticketId;

    @Column(name = "maker_id", nullable = false, length = 64)
    private String makerId;

    @Column(name = "checker_id", nullable = false, length = 64)
    private String checkerId;

    @Column(name = "original_tx_id", nullable = false, length = 64)
    private String originalTxId;

    @Column(name = "reversal_tx_id", nullable = false, length = 64)
    private String reversalTxId;

    @Column(name = "approved_at", nullable = false)
    @Builder.Default
    private Instant approvedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
