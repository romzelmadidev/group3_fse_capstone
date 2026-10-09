package com.bank.ledger.contracts.dto;

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

    @JsonProperty("ticket_id")
    private String ticketId;

    @JsonProperty("original_transaction_id")
    private String originalTransactionId;

    @JsonProperty("maker_id")
    private String makerId;

    @JsonProperty("checker_id")
    private String checkerId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("dispute_reason")
    private String disputeReason;

    @JsonProperty("maker_notes")
    private String makerNotes;

    @JsonProperty("checker_notes")
    private String checkerNotes;

    @JsonProperty("reversal_transaction_id")
    private String reversalTransactionId;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("resolved_at")
    private Instant resolvedAt;
}
