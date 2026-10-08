package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "transaction_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionStatusHistoryMaster {

    @Id
    @Column(name = "history_id", length = 64)
    private String historyId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "from_status", length = 30)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 30)
    private String toStatus;

    @Column(name = "change_reason", nullable = false, length = 100)
    private String changeReason;

    @Column(name = "reason_details", length = 500)
    private String reasonDetails;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(name = "actor_type", nullable = false, length = 30)
    private String actorType;

    @Column(name = "changed_at", nullable = false)
    @Builder.Default
    private Instant changedAt = Instant.now();

    @Lob
    @Column(name = "metadata_json")
    private String metadataJson;
}
