package com.bank.ledger.contracts.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object representing a participant bank in the interbank network.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParticipantBankDto {

    @JsonProperty("bankCode")
    @JsonAlias("bank_code")
    private String bankCode;

    @JsonProperty("bankName")
    @JsonAlias("bank_name")
    private String bankName;

    @JsonProperty("bicSwiftCode")
    @JsonAlias("bic_swift_code")
    private String bicSwiftCode;

    @JsonProperty("clearingNetwork")
    @JsonAlias("clearing_network")
    private String clearingNetwork;

    @JsonProperty("nostroAccountId")
    @JsonAlias("nostro_account_id")
    private String nostroAccountId;

    @JsonProperty("apiEndpointUrl")
    @JsonAlias({"api_endpoint_url", "default_api_url", "defaultApiUrl"})
    private String apiEndpointUrl;

    @JsonProperty("apiKey")
    @JsonAlias({"api_key"})
    private String apiKey;

    @JsonProperty("isActive")
    @JsonAlias({"is_active", "active"})
    private Integer isActive;

    @JsonProperty("settlementCurrency")
    @JsonAlias("settlement_currency")
    private String settlementCurrency;
}
