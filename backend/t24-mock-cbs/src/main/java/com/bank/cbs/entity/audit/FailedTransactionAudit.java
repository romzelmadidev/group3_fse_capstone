package com.bank.cbs.entity.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "failed_transaction_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailedTransactionAudit {

    @Id
    @Column(name = "incident_id", length = 64)
    private String incidentId;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "transaction_id", nullable = false, length = 64)
    private String transactionId;

    @Column(name = "error_type", nullable = false, length = 100)
    private String errorType;

    @Column(name = "error_code", nullable = false, length = 50)
    private String errorCode;

    @Column(name = "circuit_breaker_state", nullable = false, length = 20)
    private String circuitBreakerState;

    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    @Lob
    @Column(name = "stack_trace")
    private String stackTrace;

    @Column(name = "replay_status", nullable = false, length = 30)
    @Builder.Default
    private String replayStatus = "PENDING_REPLAY"; // PENDING_REPLAY, REPLAYED, DISMISSED

    @Column(name = "failure_timestamp", nullable = false)
    @Builder.Default
    private Instant failureTimestamp = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 64)
    private String resolvedBy;
}
