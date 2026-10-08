package com.bank.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;

public record BiometricVerificationRequest(
        @NotBlank String transactionId,
        @NotBlank String deviceId,
        @NotBlank String challengeToken,
        @NotBlank String assertionSignature
) {}
