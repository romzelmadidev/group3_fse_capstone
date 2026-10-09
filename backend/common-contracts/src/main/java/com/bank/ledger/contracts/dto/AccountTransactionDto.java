package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Data Transfer Object representing an account transaction ledger record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountTransactionDto {

    @JsonProperty("transaction_id")
    @JsonAlias("transactionId")
    private String transactionId;

    @JsonProperty("source_account_id")
    @JsonAlias("sourceAccountId")
    private String sourceAccountId;

    @JsonProperty("target_account_id")
    @JsonAlias("targetAccountId")
    private String targetAccountId;

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("transaction_type")
    @JsonAlias("transactionType")
    private String transactionType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("memo")
    private String memo;

    @JsonProperty("created_at")
    @JsonAlias("createdAt")
    private Instant createdAt;
}
