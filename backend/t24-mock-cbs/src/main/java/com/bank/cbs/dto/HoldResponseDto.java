package com.bank.cbs.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record HoldResponseDto(
        String referenceId,
        String accountId,
        BigDecimal amount,
        String status,
        BigDecimal balanceAmount,
        BigDecimal holdAmount,
        BigDecimal availableBalance,
        String message,
        Instant timestamp
) {}
