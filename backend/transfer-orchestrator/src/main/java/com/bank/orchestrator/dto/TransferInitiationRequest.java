package com.bank.orchestrator.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TransferInitiationRequest(
        String transactionId,
        @NotBlank(message = "Source account ID is required")
        String sourceAccountId,
        @NotBlank(message = "Destination account ID is required")
        String destinationAccountId,
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,
        String currency,
        String description,
        String deviceId,
        String idempotencyKey,
        String biometricSignature,
        boolean scamAdvisoryAcknowledged
) {}
