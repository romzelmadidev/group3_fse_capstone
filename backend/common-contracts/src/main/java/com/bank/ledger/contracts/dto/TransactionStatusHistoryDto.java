package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    @JsonProperty("historyId")
    @JsonAlias("history_id")
    private String historyId;

    @JsonProperty("transactionId")
    @JsonAlias("transaction_id")
    private String transactionId;

    @JsonProperty("fromStatus")
    @JsonAlias("from_status")
    private String fromStatus;

    @JsonProperty("toStatus")
    @JsonAlias("to_status")
    private String toStatus;

    @JsonProperty("changeReason")
    @JsonAlias("change_reason")
    private String changeReason;

    @JsonProperty("reasonDetails")
    @JsonAlias("reason_details")
    private String reasonDetails;

    @JsonProperty("actorId")
    @JsonAlias("actor_id")
    private String actorId;

    @JsonProperty("actorType")
    @JsonAlias("actor_type")
    private String actorType;

    @JsonProperty("changedAt")
    @JsonAlias("changed_at")
    private Instant changedAt;
}
