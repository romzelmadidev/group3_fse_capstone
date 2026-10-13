package com.fse.banking.account.limits;

import java.math.BigDecimal;
import java.time.Instant;

public record LimitSnapshot(
        LimitTier tier,
        boolean restricted,
        BigDecimal perTransferCap,
        BigDecimal dailyCap,
        BigDecimal dailyUsed,
        BigDecimal monthlyCap,
        BigDecimal monthlyUsed,
        Instant now) {
}
