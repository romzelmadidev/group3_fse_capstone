package com.fse.banking.account.limits;

import java.math.BigDecimal;

public record Breach(
        CapKind capKind,
        BigDecimal capValue,
        BigDecimal remaining,
        LimitTier tier,
        boolean restricted) {
}
