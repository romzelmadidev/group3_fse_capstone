package com.bank.cbs.dto;

import java.math.BigDecimal;

public record BalanceEnquiryResponseDto(
        String accountId,
        String accountNumber,
        String accountType,
        String currency,
        BigDecimal currentBalance,
        BigDecimal availableBalance,
        BigDecimal holdBalance,
        String status
) {}
