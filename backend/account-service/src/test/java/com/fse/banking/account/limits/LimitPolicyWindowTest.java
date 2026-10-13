package com.fse.banking.account.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feature: kyc-tiered-transfer-limits, Property 2: Window correctness
 *
 * <p>For any instant, the daily window is the Asia/Manila calendar day containing it and the monthly
 * window the Asia/Manila calendar month. Each is [start, end) with start <= instant < end, start at
 * 00:00 Manila, and end equal to the next window's start (the reset time). An instant exactly on a
 * boundary belongs to the new window.
 *
 * <p><b>Validates: Requirements 3.1, 3.5, 6.5</b>
 */
class LimitPolicyWindowTest {

    private static final ZoneId MANILA = ZoneId.of("Asia/Manila");
    private static final int ITERATIONS = 500;

    /** windows() does not touch the properties, so none are needed for this pure test. */
    private final LimitPolicy policy = new LimitPolicy(null);

    @Test
    void randomInstantsFallInTheirManilaDayAndMonth() {
        Random random = new Random(0);
        // 2000-01-01Z .. 2060-01-01Z
        long from = Instant.parse("2000-01-01T00:00:00Z").getEpochSecond();
        long to = Instant.parse("2060-01-01T00:00:00Z").getEpochSecond();
        for (int i = 0; i < ITERATIONS; i++) {
            long seconds = from + (long) (random.nextDouble() * (to - from));
            Instant instant = Instant.ofEpochSecond(seconds, random.nextInt(1_000_000_000));
            assertWindows(instant);
        }
    }

    @Test
    void boundaryInstantsBelongToTheNewWindow() {
        for (Instant midnight : List.of(
                manila("2024-03-15T00:00:00"),
                manila("2024-02-29T00:00:00"),
                manila("2024-03-01T00:00:00"),
                manila("2023-02-01T00:00:00"),
                manila("2024-12-31T00:00:00"),
                manila("2025-01-01T00:00:00"),
                manila("2024-01-01T00:00:00"))) {
            assertWindows(midnight);
            assertWindows(midnight.minusNanos(1));
            assertWindows(midnight.plusNanos(1));

            LimitPolicy.Windows at = policy.windows(midnight);
            LimitPolicy.Windows before = policy.windows(midnight.minusNanos(1));
            // Exactly on the boundary the day window starts at the instant itself ...
            assertEquals(midnight, at.dayStart());
            // ... and one nano earlier it is still the previous day, whose end is this instant.
            assertEquals(midnight, before.dayEnd());
            assertTrue(before.dayStart().isBefore(at.dayStart()));
        }
    }

    @Test
    void monthBoundaryBelongsToNewMonth() {
        Instant firstOfMarch = manila("2024-03-01T00:00:00");
        LimitPolicy.Windows at = policy.windows(firstOfMarch);
        LimitPolicy.Windows before = policy.windows(firstOfMarch.minusNanos(1));

        assertEquals(firstOfMarch, at.monthStart());
        assertEquals(manila("2024-04-01T00:00:00"), at.monthEnd());
        assertEquals(manila("2024-02-01T00:00:00"), before.monthStart());
        assertEquals(firstOfMarch, before.monthEnd());
    }

    @Test
    void leapYearFebruaryHas29Days() {
        LimitPolicy.Windows w = policy.windows(manila("2024-02-29T12:00:00"));
        assertEquals(manila("2024-02-01T00:00:00"), w.monthStart());
        assertEquals(manila("2024-03-01T00:00:00"), w.monthEnd());
        assertEquals(manila("2024-02-29T00:00:00"), w.dayStart());
        assertEquals(manila("2024-03-01T00:00:00"), w.dayEnd());

        // Non-leap February ends after day 28.
        LimitPolicy.Windows nonLeap = policy.windows(manila("2023-02-28T23:59:59"));
        assertEquals(manila("2023-03-01T00:00:00"), nonLeap.dayEnd());
        assertEquals(manila("2023-03-01T00:00:00"), nonLeap.monthEnd());
    }

    @Test
    void decemberRollsOverToJanuary() {
        LimitPolicy.Windows w = policy.windows(manila("2024-12-31T23:59:59"));
        assertEquals(manila("2024-12-31T00:00:00"), w.dayStart());
        assertEquals(manila("2025-01-01T00:00:00"), w.dayEnd());
        assertEquals(manila("2024-12-01T00:00:00"), w.monthStart());
        assertEquals(manila("2025-01-01T00:00:00"), w.monthEnd());
    }

    @Test
    void manilaMidnightIsSixteenHundredUtcOfPreviousDay() {
        // Fixed UTC+8 literal, independent of the zone database, as a sanity check on the oracle itself.
        Instant instant = Instant.parse("2024-06-10T15:59:59.999999999Z"); // still 2024-06-10 in Manila
        LimitPolicy.Windows w = policy.windows(instant);
        assertEquals(Instant.parse("2024-06-09T16:00:00Z"), w.dayStart());
        assertEquals(Instant.parse("2024-06-10T16:00:00Z"), w.dayEnd());

        LimitPolicy.Windows next = policy.windows(Instant.parse("2024-06-10T16:00:00Z"));
        assertEquals(Instant.parse("2024-06-10T16:00:00Z"), next.dayStart());
        assertEquals(Instant.parse("2024-06-11T16:00:00Z"), next.dayEnd());
    }

    private void assertWindows(Instant instant) {
        LimitPolicy.Windows w = policy.windows(instant);

        // Independent java.time oracle.
        ZonedDateTime local = instant.atZone(MANILA);
        ZonedDateTime dayStart = local.truncatedTo(ChronoUnit.DAYS);
        ZonedDateTime dayEnd = dayStart.plusDays(1);
        ZonedDateTime monthStart = dayStart.withDayOfMonth(1);
        ZonedDateTime monthEnd = monthStart.plusMonths(1);

        assertEquals(dayStart.toInstant(), w.dayStart(), "dayStart for " + instant);
        assertEquals(dayEnd.toInstant(), w.dayEnd(), "dayEnd for " + instant);
        assertEquals(monthStart.toInstant(), w.monthStart(), "monthStart for " + instant);
        assertEquals(monthEnd.toInstant(), w.monthEnd(), "monthEnd for " + instant);

        // [start, end) containment.
        assertFalse(instant.isBefore(w.dayStart()), "start <= instant (day) for " + instant);
        assertTrue(instant.isBefore(w.dayEnd()), "instant < end (day) for " + instant);
        assertFalse(instant.isBefore(w.monthStart()), "start <= instant (month) for " + instant);
        assertTrue(instant.isBefore(w.monthEnd()), "instant < end (month) for " + instant);

        // Starts are at 00:00 Manila; month starts on day 1.
        assertEquals(java.time.LocalTime.MIDNIGHT, w.dayStart().atZone(MANILA).toLocalTime());
        assertEquals(java.time.LocalTime.MIDNIGHT, w.monthStart().atZone(MANILA).toLocalTime());
        assertEquals(1, w.monthStart().atZone(MANILA).getDayOfMonth());

        // end is the next window's start (the reset time).
        LimitPolicy.Windows nextDay = policy.windows(w.dayEnd());
        assertEquals(w.dayEnd(), nextDay.dayStart(), "next day starts at reset for " + instant);
        LimitPolicy.Windows nextMonth = policy.windows(w.monthEnd());
        assertEquals(w.monthEnd(), nextMonth.monthStart(), "next month starts at reset for " + instant);
    }

    private static Instant manila(String localDateTime) {
        return java.time.LocalDateTime.parse(localDateTime).atZone(MANILA).toInstant();
    }
}
