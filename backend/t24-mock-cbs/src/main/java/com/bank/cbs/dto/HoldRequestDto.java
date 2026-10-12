package com.bank.cbs.dto;

import java.math.BigDecimal;

public record HoldRequestDto(
        String referenceId,
        String accountId,
        BigDecimal amount,
        String currency,
        String reason
) {}
