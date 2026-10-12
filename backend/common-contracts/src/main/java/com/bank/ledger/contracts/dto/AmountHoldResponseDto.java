package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response payload for an amount hold / reservation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmountHoldResponseDto {

    @JsonProperty("hold_id")
    private String holdId;

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("hold_amount")
    private BigDecimal holdAmount;

    @JsonProperty("status")
    private String status; // ACTIVE, RELEASED, CAPTURED

    @JsonProperty("t24_lock_reference")
    private String t24LockReference;

    @JsonProperty("external_reference")
    private String externalReference;

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("target_account_id")
    private String targetAccountId;

    @JsonProperty("expires_at")
    private Instant expiresAt;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("ofs_response")
    private String ofsResponse;

    @JsonProperty("message")
    private String message;
}
