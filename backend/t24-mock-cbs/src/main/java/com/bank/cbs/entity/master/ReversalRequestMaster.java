package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "reversal_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReversalRequestMaster {

    @Id
    @Column(name = "ticket_id", length = 64)
    private String ticketId;

    @Column(name = "original_tx_id", nullable = false, length = 64)
    private String originalTxId;

    @Column(name = "maker_id", nullable = false, length = 64)
    private String makerId;

    @Column(name = "checker_id", length = 64)
    private String checkerId;

    @Column(name = "dispute_reason", nullable = false, length = 100)
    private String disputeReason;

    @Column(name = "maker_notes", nullable = false, length = 500)
    private String makerNotes;

    @Column(name = "checker_notes", length = 500)
    private String checkerNotes;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED

    @Column(name = "reversal_tx_id", length = 64)
    private String reversalTxId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
