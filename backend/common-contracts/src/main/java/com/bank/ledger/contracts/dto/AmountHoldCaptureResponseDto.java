package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response resulting from capturing an amount hold into a posted debit/credit settlement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmountHoldCaptureResponseDto {

    @JsonProperty("hold_id")
    private String holdId;

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("source_account_id")
    private String sourceAccountId;

    @JsonProperty("target_account_id")
    private String targetAccountId;

    @JsonProperty("settled_amount")
    private BigDecimal settledAmount;

    @JsonProperty("source_new_balance")
    private BigDecimal sourceNewBalance;

    @JsonProperty("source_new_available")
    private BigDecimal sourceNewAvailable;

    @JsonProperty("target_new_balance")
    private BigDecimal targetNewBalance;

    @JsonProperty("target_new_available")
    private BigDecimal targetNewAvailable;

    @JsonProperty("status")
    private String status; // CAPTURED

    @JsonProperty("message")
    private String message;

    @JsonProperty("ofs_response")
    private String ofsResponse;

    @JsonProperty("captured_at")
    private Instant capturedAt;
}
