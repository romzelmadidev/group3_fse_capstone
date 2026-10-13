package com.fse.banking.account.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fse.banking.common.enums.KycStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feature: kyc-tiered-transfer-limits, Property 4: Remaining amount and snapshot shape.
 * For any cap and used values, remaining = max(0, cap - used) and is never negative. The Limits_Endpoint
 * JSON carries tier, restricted, kyc_status, currency, per_transfer_cap and daily/monthly
 * {cap, used, remaining, resets_at}; resets_at is an ISO offset date-time (+08:00) equal to the window
 * end; and parsing the serialized JSON returns an equal response.
 *
 * <p>Validates: Requirements 6.1
 */
class LimitSnapshotJsonTest {

    private static final int ITERATIONS = 500;
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** windows() never touches the properties, so the policy is built without them. */
    private final LimitPolicy policy = new LimitPolicy(null);

    @Test
    void snapshotJsonHasExpectedShapeRemainingNeverNegativeAndRoundTrips() throws Exception {
        Random rnd = new Random(0);
        KycStatus[] kycValues = KycStatus.values();
        LimitTier[] tiers = LimitTier.values();
        int overUsed = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            BigDecimal perTransfer = money(rnd, 0, 100_000_00);
            BigDecimal dailyCap = money(rnd, 0, 500_000_00);
            BigDecimal monthlyCap = money(rnd, 0, 2_000_000_00);
            BigDecimal dailyUsed = used(rnd, dailyCap);
            BigDecimal monthlyUsed = used(rnd, monthlyCap);
            if (dailyUsed.compareTo(dailyCap) > 0 || monthlyUsed.compareTo(monthlyCap) > 0) {
                overUsed++;
            }
            boolean restricted = rnd.nextInt(4) == 0;
            LimitTier tier = tiers[rnd.nextInt(tiers.length)];
            KycStatus kyc = rnd.nextInt(10) == 0 ? null : kycValues[rnd.nextInt(kycValues.length)];
            Instant now = instant(rnd);

            LimitSnapshot snapshot = new LimitSnapshot(tier, restricted, perTransfer, dailyCap, dailyUsed,
                    monthlyCap, monthlyUsed, now);
            LimitPolicy.Windows windows = policy.windows(now);
            LimitService.View view = new LimitService.View(snapshot, kyc, windows);

            LimitsController.LimitsResponse response = LimitsController.LimitsResponse.from(view);
            String json = mapper.writeValueAsString(response);
            JsonNode root = mapper.readTree(json);

            // Shape
            assertEquals(tier.name(), root.get("tier").asText(), json);
            assertTrue(root.get("restricted").isBoolean(), json);
            assertEquals(restricted, root.get("restricted").asBoolean(), json);
            assertEquals(kyc == null ? "PENDING" : kyc.name(), root.get("kyc_status").asText(), json);
            assertEquals("PHP", root.get("currency").asText(), json);
            assertDecimal(perTransfer, root.get("per_transfer_cap"), json);

            assertWindow(root.get("daily"), dailyCap, dailyUsed, windows.dayEnd(), json);
            assertWindow(root.get("monthly"), monthlyCap, monthlyUsed, windows.monthEnd(), json);

            // Round trip: parse the JSON back and compare with the original response.
            LimitsController.LimitsResponse parsed = mapper.readValue(json, LimitsController.LimitsResponse.class);
            assertEquals(response, parsed, json);
            assertEquals(json, mapper.writeValueAsString(parsed));
        }
        assertTrue(overUsed > 0, "generator must produce used > cap cases");
    }

    private void assertWindow(JsonNode window, BigDecimal cap, BigDecimal used, Instant windowEnd, String json) {
        assertNotNull(window, json);
        for (String field : new String[] {"cap", "used", "remaining", "resets_at"}) {
            assertTrue(window.has(field), "missing " + field + " in " + json);
        }
        assertDecimal(cap, window.get("cap"), json);
        assertDecimal(used, window.get("used"), json);

        BigDecimal remaining = window.get("remaining").decimalValue();
        assertTrue(remaining.signum() >= 0, "negative remaining in " + json);
        BigDecimal expected = cap.subtract(used).max(BigDecimal.ZERO);
        assertEquals(0, expected.compareTo(remaining), json);
        assertEquals(0, expected.compareTo(LimitPolicy.remaining(cap, used)), json);

        String resetsAt = window.get("resets_at").asText();
        assertTrue(resetsAt.endsWith("+08:00"), resetsAt);
        OffsetDateTime parsed = OffsetDateTime.parse(resetsAt);
        assertEquals(ZoneOffset.ofHours(8), parsed.getOffset(), resetsAt);
        assertEquals(windowEnd, parsed.toInstant(), resetsAt);
        assertEquals(0, parsed.getHour() + parsed.getMinute() + parsed.getSecond() + parsed.getNano(), resetsAt);
        assertFalse(resetsAt.contains("["), resetsAt);
    }

    private static void assertDecimal(BigDecimal expected, JsonNode actual, String json) {
        assertNotNull(actual, json);
        assertTrue(actual.isNumber(), json);
        assertEquals(0, expected.setScale(2, RoundingMode.HALF_UP).compareTo(actual.decimalValue()), json);
    }

    /** Cents-precision money in [minCents, maxCents], with boundary values drawn on purpose. */
    private static BigDecimal money(Random rnd, long minCents, long maxCents) {
        long cents;
        switch (rnd.nextInt(8)) {
            case 0 -> cents = minCents;
            case 1 -> cents = maxCents;
            default -> cents = minCents + (long) (rnd.nextDouble() * (maxCents - minCents + 1));
        }
        return BigDecimal.valueOf(Math.min(cents, maxCents), 2);
    }

    /** Used equal to cap, above cap, zero, or anywhere in between. */
    private static BigDecimal used(Random rnd, BigDecimal cap) {
        return switch (rnd.nextInt(5)) {
            case 0 -> cap;
            case 1 -> BigDecimal.ZERO.setScale(2);
            case 2 -> cap.add(BigDecimal.valueOf(1 + rnd.nextInt(1_000_000), 2));
            default -> cap.multiply(BigDecimal.valueOf(rnd.nextDouble())).setScale(2, RoundingMode.HALF_UP);
        };
    }

    /** Random instant over ~3 years, sometimes landing exactly on a Manila midnight / month boundary. */
    private Instant instant(Random rnd) {
        Instant t = BASE.plusSeconds((long) (rnd.nextDouble() * 3 * 365 * 24 * 3600));
        if (rnd.nextInt(5) == 0) {
            LimitPolicy.Windows w = policy.windows(t);
            return switch (rnd.nextInt(4)) {
                case 0 -> w.dayStart();
                case 1 -> w.dayEnd();
                case 2 -> w.monthStart();
                default -> w.monthEnd().minusSeconds(1);
            };
        }
        return t;
    }
}
