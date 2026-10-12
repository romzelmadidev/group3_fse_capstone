package com.bank.orchestrator.dto;

import java.math.BigDecimal;

public record RiskScoreRequest(
        String transactionId,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        String currency,
        String deviceId
) {}
