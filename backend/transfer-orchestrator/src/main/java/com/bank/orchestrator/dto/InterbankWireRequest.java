package com.bank.orchestrator.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Standardized Bilateral Inter-Bank Wire Transfer Request Payload.
 * Agreed wire format between AuraBank (Group 3) and Partner Bank (Group B).
 */
public record InterbankWireRequest(
        @NotBlank(message = "referenceId is required")
        String referenceId,
        @NotBlank(message = "originatingBank is required")
        String originatingBank,
        String senderName,
        @NotBlank(message = "senderAccountId is required")
        String senderAccountId,
        @NotBlank(message = "beneficiaryAccountId is required")
        String beneficiaryAccountId,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be positive")
        BigDecimal amount,
        String currency,
        String timestamp
) {}
