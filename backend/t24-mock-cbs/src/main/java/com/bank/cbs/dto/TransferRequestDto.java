package com.bank.cbs.dto;

import java.math.BigDecimal;

public record TransferRequestDto(
        String transactionId,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        String currency,
        String description,
        String channel,
        String idempotencyKey
) {}
