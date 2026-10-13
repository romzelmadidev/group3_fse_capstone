package com.fse.banking.account.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feature: kyc-tiered-transfer-limits, Property 6: Breach naming
 *
 * <p>For any rejected decision, capKind is the breached cap with the lowest remaining amount
 * (ties: PER_TRANSFER, DAILY, MONTHLY), and capValue and remaining are the values of that cap.
 *
 * <p><b>Validates: Requirements 4.5, 5.1</b>
 */
class LimitPolicyBreachNamingTest {

    private static final int ITERATIONS = 1000;
    private static final Instant NOW = Instant.parse("2026-10-08T03:14:15Z");

    /** evaluate() does not touch the properties, so none are needed for this pure test. */
    private final LimitPolicy policy = new LimitPolicy(null);

    private record Candidate(CapKind kind, BigDecimal cap, BigDecimal remaining) {
    }

    /** Independent oracle: the breached cap with the lowest remaining; ties resolved by CapKind order. */
    private static Candidate expectedBreach(LimitSnapshot s, BigDecimal amount) {
        BigDecimal dailyLeft = s.dailyCap().subtract(s.dailyUsed());
        BigDecimal monthlyLeft = s.monthlyCap().subtract(s.monthlyUsed());
        List<Candidate> all = new ArrayList<>();
        all.add(new Candidate(CapKind.PER_TRANSFER, s.perTransferCap(), s.perTransferCap()));
        all.add(new Candidate(CapKind.DAILY, s.dailyCap(), dailyLeft.signum() < 0 ? BigDecimal.ZERO : dailyLeft));
        all.add(new Candidate(CapKind.MONTHLY, s.monthlyCap(),
                monthlyLeft.signum() < 0 ? BigDecimal.ZERO : monthlyLeft));
        return all.stream()
                .filter(c -> amount.compareTo(c.remaining()) > 0)
                .min(Comparator.comparing(Candidate::remaining).thenComparing(c -> c.kind().ordinal()))
                .orElse(null);
    }

    private static void assertMoney(BigDecimal expected, BigDecimal actual, String what) {
        assertNotNull(actual, what);
        assertEquals(0, expected.compareTo(actual), what + ": expected " + expected + " but was " + actual);
    }

    private static BigDecimal money(int units) {
        return BigDecimal.valueOf(units).setScale(2);
    }

    private static LimitSnapshot snapshot(LimitTier tier, boolean restricted, int perTransfer, int dailyCap,
            int dailyUsed, int monthlyCap, int monthlyUsed) {
        return new LimitSnapshot(tier, restricted, money(perTransfer), money(dailyCap), money(dailyUsed),
                money(monthlyCap), money(monthlyUsed), NOW);
    }

    private void assertNamedBreach(LimitSnapshot s, int amount, CapKind kind, int capValue, int remaining) {
        Decision d = policy.evaluate(s, money(amount));
        assertFalse(d.allowed());
        Breach b = d.breach();
        assertNotNull(b);
        assertEquals(kind, b.capKind());
        assertMoney(money(capValue), b.capValue(), "capValue");
        assertMoney(money(remaining), b.remaining(), "remaining");
        assertEquals(s.tier(), b.tier());
        assertEquals(s.restricted(), b.restricted());
    }

    @Test
    void randomSnapshotsNameTheBreachedCapWithLowestRemaining() {
        Random random = new Random(0);
        LimitTier[] tiers = LimitTier.values();
        int rejected = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            LimitTier tier = tiers[random.nextInt(tiers.length)];
            boolean restricted = random.nextInt(5) == 0;
            // Small grid so caps, remainders and the amount collide often (ties and boundaries).
            int perTransfer = 100 * random.nextInt(6);
            int dailyCap = 100 * random.nextInt(8);
            int dailyUsed = 100 * random.nextInt(10); // may exceed the cap: remaining clamps to 0
            int monthlyCap = 100 * random.nextInt(10);
            int monthlyUsed = 100 * random.nextInt(12);
            LimitSnapshot s = restricted
                    ? snapshot(tier, true, 0, 0, dailyUsed, 0, monthlyUsed)
                    : snapshot(tier, false, perTransfer, dailyCap, dailyUsed, monthlyCap, monthlyUsed);

            BigDecimal amount = money(1 + random.nextInt(1000));
            switch (random.nextInt(4)) {
                case 0 -> amount = s.perTransferCap().signum() > 0 ? s.perTransferCap() : amount;
                case 1 -> amount = nonZeroOr(amount, s.dailyCap().subtract(s.dailyUsed()));
                case 2 -> amount = nonZeroOr(amount, s.monthlyCap().subtract(s.monthlyUsed()));
                default -> { }
            }
            if (random.nextBoolean()) {
                amount = amount.add(new BigDecimal("0.01"));
            }

            Decision d = policy.evaluate(s, amount);
            if (restricted) {
                assertFalse(d.allowed());
                Breach b = d.breach();
                assertEquals(CapKind.PER_TRANSFER, b.capKind());
                assertMoney(BigDecimal.ZERO, b.capValue(), "restricted capValue");
                assertMoney(BigDecimal.ZERO, b.remaining(), "restricted remaining");
                assertTrue(b.restricted());
                assertEquals(tier, b.tier());
                rejected++;
                continue;
            }

            Candidate expected = expectedBreach(s, amount);
            if (expected == null) {
                assertTrue(d.allowed(), "no cap breached, expected allowed for " + s + " amount " + amount);
                assertNull(d.breach());
                continue;
            }
            rejected++;
            String ctx = " for " + s + " amount " + amount;
            assertFalse(d.allowed(), "expected rejection" + ctx);
            Breach b = d.breach();
            assertNotNull(b, "breach present" + ctx);
            assertEquals(expected.kind(), b.capKind(), "capKind" + ctx);
            assertMoney(expected.cap(), b.capValue(), "capValue" + ctx);
            assertMoney(expected.remaining(), b.remaining(), "remaining" + ctx);
            assertEquals(tier, b.tier(), "tier" + ctx);
            assertFalse(b.restricted(), "restricted" + ctx);
            if (b.capKind() == CapKind.PER_TRANSFER) {
                assertMoney(b.capValue(), b.remaining(), "PER_TRANSFER remaining equals cap" + ctx);
            }
        }
        assertTrue(rejected >= 100, "generator should produce many rejections, got " + rejected);
    }

    private static BigDecimal nonZeroOr(BigDecimal fallback, BigDecimal candidate) {
        return candidate.signum() > 0 ? candidate : fallback;
    }

    // ---- explicit tie cases -------------------------------------------------

    @Test
    void tieBetweenPerTransferAndDailyPicksPerTransfer() {
        // per-transfer 1000, daily remaining 1500-500 = 1000, monthly roomy.
        LimitSnapshot s = snapshot(LimitTier.BASIC, false, 1000, 1500, 500, 50000, 0);
        assertNamedBreach(s, 1200, CapKind.PER_TRANSFER, 1000, 1000);
    }

    @Test
    void tieBetweenDailyAndMonthlyPicksDaily() {
        // per-transfer roomy, daily remaining 800, monthly remaining 800.
        LimitSnapshot s = snapshot(LimitTier.BASIC, false, 5000, 1000, 200, 10000, 9200);
        assertNamedBreach(s, 900, CapKind.DAILY, 1000, 800);
    }

    @Test
    void tieBetweenPerTransferAndMonthlyPicksPerTransfer() {
        // per-transfer 1000, daily roomy (not breached), monthly remaining 1000.
        LimitSnapshot s = snapshot(LimitTier.FULLY_VERIFIED, false, 1000, 5000, 0, 3000, 2000);
        assertNamedBreach(s, 1200, CapKind.PER_TRANSFER, 1000, 1000);
    }

    @Test
    void threeWayTiePicksPerTransfer() {
        LimitSnapshot s = snapshot(LimitTier.BASIC, false, 1000, 1500, 500, 3000, 2000);
        assertNamedBreach(s, 1001, CapKind.PER_TRANSFER, 1000, 1000);
    }

    @Test
    void tieAtZeroRemainingPicksPerTransferOverExhaustedDailyAndMonthly() {
        // per-transfer cap 0, daily and monthly fully used (remaining 0 for all three).
        LimitSnapshot s = snapshot(LimitTier.BASIC, false, 0, 1000, 1000, 5000, 5000);
        assertNamedBreach(s, 1, CapKind.PER_TRANSFER, 0, 0);
    }

    @Test
    void tieAtZeroBetweenDailyAndMonthlyPicksDaily() {
        LimitSnapshot s = snapshot(LimitTier.BASIC, false, 5000, 1000, 1000, 5000, 5000);
        assertNamedBreach(s, 1, CapKind.DAILY, 1000, 0);
    }

    @Test
    void overUsedDailyClampsRemainingToZeroAndStillNamesDaily() {
        LimitSnapshot s = snapshot(LimitTier.BASIC, false, 5000, 1000, 1500, 5000, 100);
        assertNamedBreach(s, 10, CapKind.DAILY, 1000, 0);
    }

    @Test
    void lowestRemainingWinsOverEarlierKind() {
        // per-transfer 5000, daily remaining 3000, monthly remaining 2000; amount breaches all.
        LimitSnapshot s = snapshot(LimitTier.FULLY_VERIFIED, false, 5000, 4000, 1000, 10000, 8000);
        assertNamedBreach(s, 6000, CapKind.MONTHLY, 10000, 2000);
    }

    @Test
    void onlyBreachedCapsAreCandidates() {
        // monthly remaining (100) is lowest but amount 150 > 100 breaches it; daily remaining 500 is not breached.
        LimitSnapshot s = snapshot(LimitTier.BASIC, false, 1000, 1000, 500, 1000, 900);
        assertNamedBreach(s, 150, CapKind.MONTHLY, 1000, 100);
        // amount 100 equals monthly remaining: allowed (equality passes).
        assertTrue(policy.evaluate(s, money(100)).allowed());
    }

    // ---- restricted ---------------------------------------------------------

    @Test
    void restrictedSnapshotNamesPerTransferWithZeroValues() {
        LimitSnapshot s = snapshot(LimitTier.BASIC, true, 0, 0, 0, 0, 0);
        assertNamedBreach(s, 1, CapKind.PER_TRANSFER, 0, 0);
        Breach b = policy.evaluate(s, money(1)).breach();
        assertTrue(b.restricted());
        assertEquals(LimitTier.BASIC, b.tier());
    }

    @Test
    void restrictedFullyVerifiedKeepsTierInBreach() {
        LimitSnapshot s = snapshot(LimitTier.FULLY_VERIFIED, true, 0, 0, 0, 0, 0);
        assertNamedBreach(s, 500, CapKind.PER_TRANSFER, 0, 0);
    }
}
