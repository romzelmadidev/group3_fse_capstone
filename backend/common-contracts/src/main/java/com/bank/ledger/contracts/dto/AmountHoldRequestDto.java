package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request payload for placing an amount hold / reservation on an account.
 * Maps to Temenos OFS AC.LOCKED.EVENTS,INPUT.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AmountHoldRequestDto {

    @NotBlank(message = "account_id is required")
    @JsonProperty("account_id")
    @JsonAlias({"account_id", "accountId", "accountNumber", "account_number"})
    private String accountId;

    @NotNull(message = "hold_amount is required")
    @DecimalMin(value = "0.01", message = "hold_amount must be strictly positive")
    @JsonProperty("hold_amount")
    @JsonAlias({"hold_amount", "holdAmount", "amount", "locked_amount", "lockedAmount"})
    private BigDecimal holdAmount;

    @JsonProperty("reason")
    @JsonAlias({"reason", "hold_reason", "holdReason"})
    @Builder.Default
    private String reason = "MAKER_CHECKER_HOLD";

    @JsonProperty("expiry_hours")
    @JsonAlias({"expiry_hours", "expiryHours"})
    @Builder.Default
    private Integer expiryHours = 24;

    @JsonProperty("external_reference")
    @JsonAlias({"external_reference", "externalReference", "ref", "reference"})
    private String externalReference;

    @JsonProperty("transaction_id")
    @JsonAlias({"transaction_id", "transactionId", "tx_id", "txId", "reference_txn_id", "referenceTxnId"})
    private String transactionId;

    @JsonProperty("target_account_id")
    @JsonAlias({"target_account_id", "targetAccountId", "destination_account_id", "destinationAccountId", "beneficiary_account_id"})
    private String targetAccountId;

    @JsonProperty("ofs_message")
    @JsonAlias({"ofs_message", "ofsMessage", "raw_ofs", "rawOfs"})
    private String ofsMessage;
}
