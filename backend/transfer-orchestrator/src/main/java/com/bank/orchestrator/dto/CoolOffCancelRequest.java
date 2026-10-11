package com.bank.orchestrator.dto;

import jakarta.validation.constraints.NotBlank;

public record CoolOffCancelRequest(
        @NotBlank String transactionId,
        @NotBlank String reason
) {}
