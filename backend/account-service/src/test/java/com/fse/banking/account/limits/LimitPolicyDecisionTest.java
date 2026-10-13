package com.fse.banking.account.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feature: kyc-tiered-transfer-limits, Property 5: Decision correctness.
 * For any snapshot and positive amount, allowed iff amount <= perTransferCap, amount <= dailyRemaining
 * and amount <= monthlyRemaining (equality allowed). Restricted snapshots reject every positive amount
 * with restricted = true.
 *
 * <p>Validates: Requirements 4.2, 4.3, 4.4, 4.6, 4.8
 */
class LimitPolicyDecisionTest {

    private static final int ITERATIONS = 2000;

    /** evaluate() never touches the properties, so the policy is built without them. */
    private final LimitPolicy policy = new LimitPolicy(null);

    @Test
    void decisionIsAllowedIffAmountWithinPerTransferCapDailyRemainingAndMonthlyRemaining() {
        Random rnd = new Random(0);
        int allowedCount = 0;
        int rejectedCount = 0;
        int equalityCount = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            BigDecimal perTransferCap = money(rnd, 0, 100_000_00);
            BigDecimal dailyCap = money(rnd, 0, 500_000_00);
            BigDecimal monthlyCap = money(rnd, 0, 2_000_000_00);
            BigDecimal dailyUsed = used(rnd, dailyCap);
            BigDecimal monthlyUsed = used(rnd, monthlyCap);
            LimitTier tier = rnd.nextBoolean() ? LimitTier.BASIC : LimitTier.FULLY_VERIFIED;
            LimitSnapshot s = new LimitSnapshot(tier, false, perTransferCap, dailyCap, dailyUsed,
                    monthlyCap, monthlyUsed, Instant.EPOCH);

            // Independent oracle: remaining computed here, not via LimitPolicy.remaining.
            BigDecimal dailyRemaining = dailyCap.compareTo(dailyUsed) > 0 ? dailyCap.subtract(dailyUsed) : zero();
            BigDecimal monthlyRemaining = monthlyCap.compareTo(monthlyUsed) > 0
                    ? monthlyCap.subtract(monthlyUsed) : zero();

            BigDecimal amount = amount(rnd, perTransferCap, dailyRemaining, monthlyRemaining);
            boolean expected = amount.compareTo(perTransferCap) <= 0
                    && amount.compareTo(dailyRemaining) <= 0
                    && amount.compareTo(monthlyRemaining) <= 0;
            boolean atBoundary = amount.compareTo(perTransferCap) == 0
                    || amount.compareTo(dailyRemaining) == 0
                    || amount.compareTo(monthlyRemaining) == 0;

            Decision d = policy.evaluate(s, amount);
            String ctx = "iteration=" + i + " snapshot=" + s + " amount=" + amount;

            assertEquals(expected, d.allowed(), ctx);
            if (expected) {
                allowedCount++;
                assertNull(d.breach(), ctx);
                if (atBoundary) {
                    equalityCount++;
                }
            } else {
                rejectedCount++;
                assertNotNull(d.breach(), ctx);
                assertFalse(d.breach().restricted(), ctx);
            }
        }

        // Sanity: the generators exercised both outcomes and the equality boundary.
        assertTrue(allowedCount > 100, "allowed cases: " + allowedCount);
        assertTrue(rejectedCount > 100, "rejected cases: " + rejectedCount);
        assertTrue(equalityCount > 50, "allowed-at-boundary cases: " + equalityCount);
    }

    @Test
    void usedAtOrAboveCapMeansZeroRemainingAndRejectsEveryPositiveAmount() {
        Random rnd = new Random(0);
        for (int i = 0; i < 200; i++) {
            BigDecimal cap = money(rnd, 1, 500_000_00);
            BigDecimal over = rnd.nextBoolean() ? zero() : money(rnd, 0, 100_00);
            BigDecimal used = cap.add(over);
            BigDecimal big = cap.multiply(BigDecimal.valueOf(10));
            BigDecimal amount = rnd.nextBoolean() ? new BigDecimal("0.01") : money(rnd, 1, big.movePointRight(2).intValue());

            // Daily exhausted
            LimitSnapshot daily = new LimitSnapshot(LimitTier.BASIC, false, big, cap, used, big, zero(),
                    Instant.EPOCH);
            Decision dd = policy.evaluate(daily, amount);
            assertFalse(dd.allowed(), "daily iteration=" + i + " amount=" + amount);
            assertFalse(dd.breach().restricted());

            // Monthly exhausted
            LimitSnapshot monthly = new LimitSnapshot(LimitTier.FULLY_VERIFIED, false, big, big, zero(), cap,
                    used, Instant.EPOCH);
            Decision md = policy.evaluate(monthly, amount);
            assertFalse(md.allowed(), "monthly iteration=" + i + " amount=" + amount);
            assertFalse(md.breach().restricted());
        }
    }

    @Test
    void restrictedSnapshotRejectsEveryPositiveAmountWithRestrictedTrue() {
        Random rnd = new Random(0);
        for (int i = 0; i < ITERATIONS / 4; i++) {
            LimitTier tier = rnd.nextBoolean() ? LimitTier.BASIC : LimitTier.FULLY_VERIFIED;
            // Restricted snapshots may carry any caps/usage; they must still reject.
            BigDecimal perTransferCap = money(rnd, 0, 100_000_00);
            BigDecimal dailyCap = money(rnd, 0, 500_000_00);
            BigDecimal monthlyCap = money(rnd, 0, 2_000_000_00);
            LimitSnapshot s = new LimitSnapshot(tier, true, perTransferCap, dailyCap, used(rnd, dailyCap),
                    monthlyCap, used(rnd, monthlyCap), Instant.EPOCH);
            BigDecimal amount = rnd.nextInt(4) == 0 ? new BigDecimal("0.01") : money(rnd, 1, 1_000_000_00);

            Decision d = policy.evaluate(s, amount);

            assertFalse(d.allowed(), "iteration=" + i + " snapshot=" + s + " amount=" + amount);
            assertNotNull(d.breach());
            assertTrue(d.breach().restricted(), "iteration=" + i);
        }
    }

    /** Money with 2 decimals from minCents..maxCents (inclusive). */
    private static BigDecimal money(Random rnd, long minCents, long maxCents) {
        long range = maxCents - minCents + 1;
        long cents = minCents + (long) (rnd.nextDouble() * range);
        return BigDecimal.valueOf(cents, 2);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Biased usage: zero, partial, exactly cap, or over cap (remaining clamps to 0). */
    private static BigDecimal used(Random rnd, BigDecimal cap) {
        long capCents = cap.movePointRight(2).longValueExact();
        switch (rnd.nextInt(5)) {
            case 0:
                return zero();
            case 1:
                return BigDecimal.valueOf(capCents, 2);
            case 2:
                return BigDecimal.valueOf(capCents + 1 + rnd.nextInt(100_00), 2);
            default:
                return money(rnd, 0, Math.max(capCents, 0));
        }
    }

    /** Biased amount: at/just below/just above a limit, or random; always positive. */
    private static BigDecimal amount(Random rnd, BigDecimal... limits) {
        BigDecimal base;
        switch (rnd.nextInt(8)) {
            case 0:
            case 1: {
                // Exactly the binding (smallest) limit: allowed at equality when positive.
                BigDecimal min = limits[0];
                for (BigDecimal l : limits) {
                    min = min.min(l);
                }
                return min.signum() > 0 ? min : new BigDecimal("0.01");
            }
            case 2:
            case 3:
            case 4: {
                base = limits[rnd.nextInt(limits.length)];
                int delta = rnd.nextInt(3) - 1; // -1, 0, +1 cent around the limit
                base = base.add(BigDecimal.valueOf(delta, 2));
                break;
            }
            case 5:
                return new BigDecimal("0.01");
            default:
                return money(rnd, 1, 600_000_00);
        }
        return base.signum() > 0 ? base : new BigDecimal("0.01");
    }
}
