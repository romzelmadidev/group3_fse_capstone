package com.bank.orchestrator.dto;

import java.math.BigDecimal;

public record RiskScoreRequest(
        String transactionId,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        String currency,
        String deviceId,
        String description
) {
    public RiskScoreRequest(String transactionId, String sourceAccountId, String destinationAccountId, BigDecimal amount, String currency, String deviceId) {
        this(transactionId, sourceAccountId, destinationAccountId, amount, currency, deviceId, null);
    }
}
