package com.bank.cbs.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponseDto(
        String transactionId,
        String status,
        BigDecimal amount,
        String currency,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal sourceNewBalance,
        BigDecimal destinationNewBalance,
        String ofsResponse,
        Instant postedAt
) {}
