package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response payload for releasing an amount hold / reservation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmountHoldReleaseResponseDto {

    @JsonProperty("hold_id")
    private String holdId;

    @JsonProperty("account_id")
    private String accountId;

    @JsonProperty("released_amount")
    private BigDecimal releasedAmount;

    @JsonProperty("status")
    private String status; // RELEASED

    @JsonProperty("message")
    private String message;

    @JsonProperty("released_at")
    private Instant releasedAt;

    @JsonProperty("ofs_response")
    private String ofsResponse;
}
