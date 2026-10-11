package com.bank.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

public record BiometricVerificationRequest(
        @NotBlank String transactionId,
        @NotBlank String deviceId,
        @NotBlank String challengeToken,
        @NotBlank String assertionSignature,
        String destinationAccountId,
        BigDecimal amount
) {
    public BiometricVerificationRequest(
            String transactionId,
            String deviceId,
            String challengeToken,
            String assertionSignature
    ) {
        this(transactionId, deviceId, challengeToken, assertionSignature, null, null);
    }
}
