package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Data Transfer Object representing a reversal dispute ticket in the core banking system.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalTicketDto {

    @JsonProperty("ticketId")
    @JsonAlias("ticket_id")
    private String ticketId;

    @JsonProperty("originalTransactionId")
    @JsonAlias("original_transaction_id")
    private String originalTransactionId;

    @JsonProperty("makerId")
    @JsonAlias("maker_id")
    private String makerId;

    @JsonProperty("checkerId")
    @JsonAlias("checker_id")
    private String checkerId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("disputeReason")
    @JsonAlias("dispute_reason")
    private String disputeReason;

    @JsonProperty("makerNotes")
    @JsonAlias("maker_notes")
    private String makerNotes;

    @JsonProperty("checkerNotes")
    @JsonAlias("checker_notes")
    private String checkerNotes;

    @JsonProperty("reversalTransactionId")
    @JsonAlias("reversal_transaction_id")
    private String reversalTransactionId;

    @JsonProperty("createdAt")
    @JsonAlias("created_at")
    private Instant createdAt;

    @JsonProperty("resolvedAt")
    @JsonAlias("resolved_at")
    private Instant resolvedAt;
}
