package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Data Transfer Object representing an individual transition event in a transaction's lifecycle.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionStatusHistoryDto {

    @JsonProperty("history_id")
    private String historyId;

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("from_status")
    private String fromStatus;

    @JsonProperty("to_status")
    private String toStatus;

    @JsonProperty("change_reason")
    private String changeReason;

    @JsonProperty("reason_details")
    private String reasonDetails;

    @JsonProperty("actor_id")
    private String actorId;

    @JsonProperty("actor_type")
    private String actorType;

    @JsonProperty("changed_at")
    private Instant changedAt;
}
