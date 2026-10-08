package com.bank.orchestrator.dto;

import com.bank.ledger.contracts.enums.TransactionStatus;
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
) {}
