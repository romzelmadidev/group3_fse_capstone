package com.bank.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferInitiationRequest(
        @JsonAlias({"transactionId", "transaction_id", "reference", "refNumber", "reference_number"})
        String transactionId,

        @NotBlank(message = "Source account ID is required")
        @JsonAlias({"sourceAccountId", "source_account_id", "accountId", "account_id", "fromAccountId", "from_account_id", "fromAccount"})
        String sourceAccountId,

        @NotBlank(message = "Destination account ID is required")
        @JsonAlias({"destinationAccountId", "destination_account_id", "targetAccountId", "target_account_id", "toAccountId", "to_account_id", "toAccount"})
        String destinationAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @JsonAlias({"amount", "mutationAmount", "mutation_amount"})
        BigDecimal amount,

        String currency,

        @JsonAlias({"description", "remarks", "memo"})
        String description,

        @JsonAlias({"deviceId", "device_id"})
        String deviceId,

        @JsonAlias({"idempotencyKey", "idempotency_key"})
        String idempotencyKey,

        @JsonAlias({"biometricSignature", "biometric_signature"})
        String biometricSignature,

        @JsonAlias({"scamAdvisoryAcknowledged", "scam_advisory_acknowledged"})
        boolean scamAdvisoryAcknowledged
) {}