package com.bank.cbs.entity.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "audit_block_anchor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditBlockAnchor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "block_number", nullable = false, unique = true)
    private Long blockNumber;

    @Column(name = "start_audit_id", nullable = false)
    private Long startAuditId;

    @Column(name = "end_audit_id", nullable = false)
    private Long endAuditId;

    @Column(name = "record_count", nullable = false)
    private Integer recordCount;

    @Column(name = "merkle_root", nullable = false, length = 64)
    private String merkleRoot;

    @Column(name = "prev_block_root", nullable = false, length = 64)
    private String prevBlockRoot;

    @Column(name = "block_hash", nullable = false, length = 64)
    private String blockHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
