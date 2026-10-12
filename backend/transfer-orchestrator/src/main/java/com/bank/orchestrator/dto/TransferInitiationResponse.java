package com.bank.orchestrator.dto;

import com.bank.ledger.contracts.enums.TransactionStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

public record TransferInitiationResponse(
        String transactionId,
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        String sourceAccountId,
        String destinationAccountId,
        String message,
        boolean coolingOffRequired,
        Long coolingOffExpiresInSeconds,
        boolean biometricRequired,
        String biometricChallenge,
        Instant processedAt
) {
    @JsonProperty("transaction_id")
    public String getTransaction_id() { return transactionId; }

    @JsonProperty("t24_reference")
    public String getT24_reference() { return transactionId; }

    @JsonProperty("t24Reference")
    public String getT24Reference() { return transactionId; }
}