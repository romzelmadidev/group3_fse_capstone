package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request to capture an active amount hold into a finalized funds transfer settlement.
 * Maps to Temenos OFS FUNDS.TRANSFER,AUTH with HOLD.REF.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmountHoldCaptureRequestDto {

    @JsonProperty("hold_id")
    @JsonAlias({"hold_id", "holdId"})
    private String holdId;

    @JsonProperty("target_account_id")
    @JsonAlias({"target_account_id", "targetAccountId", "destination_account_id", "destinationAccountId"})
    private String targetAccountId;

    @JsonProperty("capture_amount")
    @JsonAlias({"capture_amount", "captureAmount", "amount"})
    private BigDecimal captureAmount;

    @JsonProperty("narrative")
    @JsonAlias({"narrative", "description"})
    private String narrative;
}
