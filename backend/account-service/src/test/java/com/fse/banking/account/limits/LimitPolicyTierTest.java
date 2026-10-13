package com.fse.banking.account.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fse.banking.common.enums.KycStatus;
import com.fse.banking.common.enums.UserStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Feature: kyc-tiered-transfer-limits, Property 1: Tier and restriction resolution
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 6.4, 6.6
 */
class LimitPolicyTierTest {

    private static final int ITERATIONS = 500;

    private static LimitProperties randomProperties(Random rnd) {
        return new LimitProperties(randomCaps(rnd), randomCaps(rnd));
    }

    private static LimitProperties.Caps randomCaps(Random rnd) {
        return new LimitProperties.Caps(money(rnd), money(rnd), money(rnd));
    }

    /** Includes 0 and strictly positive values so "caps are 0 when restricted" is not trivially true. */
    private static BigDecimal money(Random rnd) {
        return BigDecimal.valueOf(rnd.nextInt(10) == 0 ? 0 : 1 + rnd.nextInt(1_000_000), 2);
    }

    /** Every KycStatus plus null. */
    private static List<KycStatus> kycValues() {
        List<KycStatus> values = new ArrayList<>(List.of(KycStatus.values()));
        values.add(null);
        return values;
    }

    private static boolean expectedFullyVerified(KycStatus kyc) {
        return kyc == KycStatus.VERIFIED || kyc == KycStatus.APPROVED;
    }

    private static boolean expectedRestricted(UserStatus user) {
        return user == UserStatus.LOCKED || user == UserStatus.SUSPENDED;
    }

    @Test
    void tierAndRestrictionFollowInputsForRandomCapsAndAllStatusCombinations() {
        Random rnd = new Random(0);
        List<KycStatus> kycs = kycValues();
        UserStatus[] users = UserStatus.values();

        for (int i = 0; i < ITERATIONS; i++) {
            LimitProperties props = randomProperties(rnd);
            LimitPolicy policy = new LimitPolicy(props);
            // Iterations 0..N-1 sweep every combination first, then pick randomly.
            KycStatus kyc = kycs.get(i < kycs.size() * users.length
                    ? i % kycs.size() : rnd.nextInt(kycs.size()));
            UserStatus user = users[i < kycs.size() * users.length
                    ? (i / kycs.size()) % users.length : rnd.nextInt(users.length)];
            String ctx = "iteration=" + i + " kyc=" + kyc + " user=" + user;

            LimitPolicy.Resolution r = policy.resolve(kyc, user);

            assertEquals(expectedRestricted(user), r.restricted(), ctx);
            assertEquals(expectedFullyVerified(kyc) ? LimitTier.FULLY_VERIFIED : LimitTier.BASIC,
                    r.tier(), ctx);

            LimitProperties.Caps caps = policy.caps(r.tier(), r.restricted());
            if (r.restricted()) {
                assertEquals(0, BigDecimal.ZERO.compareTo(caps.perTransfer()), ctx);
                assertEquals(0, BigDecimal.ZERO.compareTo(caps.daily()), ctx);
                assertEquals(0, BigDecimal.ZERO.compareTo(caps.monthly()), ctx);
                // Remaining amounts are 0 as well (any usage, remaining = max(0, 0 - used)).
                BigDecimal used = money(rnd);
                assertEquals(0, BigDecimal.ZERO.compareTo(LimitPolicy.remaining(caps.daily(), used)), ctx);
                assertEquals(0, BigDecimal.ZERO.compareTo(LimitPolicy.remaining(caps.monthly(), used)), ctx);
            } else {
                LimitProperties.Caps configured =
                        r.tier() == LimitTier.FULLY_VERIFIED ? props.fullyVerified() : props.basic();
                assertSame(configured, caps, ctx);
            }
        }
    }

    @Test
    void nullKycIsTreatedAsBasicForEveryUserStatus() {
        LimitPolicy policy = new LimitPolicy(randomProperties(new Random(0)));
        for (UserStatus user : UserStatus.values()) {
            LimitPolicy.Resolution r = policy.resolve(null, user);
            assertEquals(LimitTier.BASIC, r.tier(), "user=" + user);
            assertEquals(expectedRestricted(user), r.restricted(), "user=" + user);
        }
    }

    @Test
    void resultDependsOnlyOnInputsSoChangedKycChangesNextResult() {
        Random rnd = new Random(0);
        List<KycStatus> kycs = kycValues();
        UserStatus[] users = UserStatus.values();

        for (int i = 0; i < ITERATIONS; i++) {
            LimitPolicy policy = new LimitPolicy(randomProperties(rnd));
            UserStatus user = users[rnd.nextInt(users.length)];
            KycStatus before = kycs.get(rnd.nextInt(kycs.size()));
            KycStatus after = kycs.get(rnd.nextInt(kycs.size()));
            String ctx = "iteration=" + i + " before=" + before + " after=" + after + " user=" + user;

            LimitPolicy.Resolution first = policy.resolve(before, user);
            // Same inputs give the same result (no hidden state or caching).
            assertEquals(first, policy.resolve(before, user), ctx);

            LimitPolicy.Resolution second = policy.resolve(after, user);
            assertEquals(expectedFullyVerified(after) ? LimitTier.FULLY_VERIFIED : LimitTier.BASIC,
                    second.tier(), ctx);
            if (expectedFullyVerified(before) != expectedFullyVerified(after)) {
                assertNotEquals(first, second, ctx);
                assertNotEquals(first.tier(), second.tier(), ctx);
            } else {
                assertEquals(first, second, ctx);
            }
            // Calling again with the original KYC status returns the original result.
            assertEquals(first, policy.resolve(before, user), ctx);
        }
    }
}
