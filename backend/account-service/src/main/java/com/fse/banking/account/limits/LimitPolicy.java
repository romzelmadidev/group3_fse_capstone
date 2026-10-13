package com.fse.banking.account.limits;

import com.fse.banking.common.enums.KycStatus;
import com.fse.banking.common.enums.UserStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.stereotype.Component;

/** Pure limit rules: tier resolution, caps, Manila windows and the allow/breach decision. */
@Component
public class LimitPolicy {

    public static final ZoneId ZONE = ZoneId.of("Asia/Manila");

    public record Resolution(LimitTier tier, boolean restricted) {
    }

    /** Each window is [start, end); end is the reset time. */
    public record Windows(Instant dayStart, Instant dayEnd, Instant monthStart, Instant monthEnd) {
    }

    private final LimitProperties properties;

    public LimitPolicy(LimitProperties properties) {
        this.properties = properties;
    }

    public Resolution resolve(KycStatus kyc, UserStatus user) {
        KycStatus effective = kyc == null ? KycStatus.PENDING : kyc;
        LimitTier tier = (effective == KycStatus.VERIFIED || effective == KycStatus.APPROVED)
                ? LimitTier.FULLY_VERIFIED
                : LimitTier.BASIC;
        boolean restricted = user == UserStatus.LOCKED || user == UserStatus.SUSPENDED;
        return new Resolution(tier, restricted);
    }

    public LimitProperties.Caps caps(LimitTier tier, boolean restricted) {
        if (restricted) {
            return new LimitProperties.Caps(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return tier == LimitTier.FULLY_VERIFIED ? properties.fullyVerified() : properties.basic();
    }

    public Windows windows(Instant now) {
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        return new Windows(
                start(today),
                start(today.plusDays(1)),
                start(firstOfMonth),
                start(firstOfMonth.plusMonths(1)));
    }

    public Decision evaluate(LimitSnapshot s, BigDecimal amount) {
        if (s.restricted()) {
            return new Decision(false,
                    new Breach(CapKind.PER_TRANSFER, BigDecimal.ZERO, BigDecimal.ZERO, s.tier(), true));
        }
        BigDecimal dailyRemaining = remaining(s.dailyCap(), s.dailyUsed());
        BigDecimal monthlyRemaining = remaining(s.monthlyCap(), s.monthlyUsed());

        Breach lowest = null;
        lowest = pick(lowest, s, CapKind.PER_TRANSFER, s.perTransferCap(), s.perTransferCap(), amount);
        lowest = pick(lowest, s, CapKind.DAILY, s.dailyCap(), dailyRemaining, amount);
        lowest = pick(lowest, s, CapKind.MONTHLY, s.monthlyCap(), monthlyRemaining, amount);
        return new Decision(lowest == null, lowest);
    }

    public static BigDecimal remaining(BigDecimal cap, BigDecimal used) {
        return cap.subtract(used).max(BigDecimal.ZERO);
    }

    /** Keeps the current breach unless this cap is breached with a strictly lower remaining (ties keep the earlier kind). */
    private static Breach pick(Breach current, LimitSnapshot s, CapKind kind, BigDecimal cap,
            BigDecimal remaining, BigDecimal amount) {
        if (amount.compareTo(remaining) <= 0) {
            return current;
        }
        if (current != null && remaining.compareTo(current.remaining()) >= 0) {
            return current;
        }
        return new Breach(kind, cap, remaining, s.tier(), false);
    }

    private static Instant start(LocalDate date) {
        return ZonedDateTime.of(date.atStartOfDay(), ZONE).toInstant();
    }
}
