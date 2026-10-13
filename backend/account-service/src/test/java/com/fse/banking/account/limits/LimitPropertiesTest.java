package com.fse.banking.account.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.fse.banking.common.enums.KycStatus;
import com.fse.banking.common.enums.UserStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Validates Requirements 2.2, 2.3 and 2.4 for {@link LimitProperties}.
 *
 * Defaults are read from the real application.properties limits.* entries.
 */
class LimitPropertiesTest {

    private static final String PREFIX = "limits.";

    private static final String[] KEYS = {
            "limits.basic.per-transfer",
            "limits.basic.daily",
            "limits.basic.monthly",
            "limits.fully-verified.per-transfer",
            "limits.fully-verified.daily",
            "limits.fully-verified.monthly"
    };

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LimitProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    /** The limits.* entries of the real application.properties. */
    private static Map<String, Object> realLimitEntries() throws IOException {
        Properties props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        Map<String, Object> limits = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                limits.put(name, props.getProperty(name));
            }
        }
        return limits;
    }

    private ApplicationContextRunner withLimits(Map<String, Object> limits) {
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("limitsUnderTest", limits)));
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    @Test
    void realApplicationPropertiesDefineExactlyTheSixLimitKeys() throws IOException {
        assertThat(realLimitEntries().keySet()).containsExactlyInAnyOrder(KEYS);
    }

    @Test
    void defaultsFromApplicationPropertiesMatchRequirements() throws IOException {
        withLimits(realLimitEntries()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LimitProperties p = ctx.getBean(LimitProperties.class);

            assertThat(p.basic().perTransfer()).isEqualByComparingTo(bd("10000"));
            assertThat(p.basic().daily()).isEqualByComparingTo(bd("10000"));
            assertThat(p.basic().monthly()).isEqualByComparingTo(bd("50000"));

            assertThat(p.fullyVerified().perTransfer()).isEqualByComparingTo(bd("50000"));
            assertThat(p.fullyVerified().daily()).isEqualByComparingTo(bd("100000"));
            assertThat(p.fullyVerified().monthly()).isEqualByComparingTo(bd("500000"));
        });
    }

    /** Requirement 1.1 / 2.1: a fresh Basic account may send PHP 10,000 under the real configured caps. */
    @Test
    void basicFreshAccountMayTransfer10000UnderRealCaps() throws IOException {
        withLimits(realLimitEntries()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LimitPolicy policy = new LimitPolicy(ctx.getBean(LimitProperties.class));
            LimitProperties.Caps caps = policy.caps(LimitTier.BASIC, false);
            LimitSnapshot snapshot = new LimitSnapshot(LimitTier.BASIC, false,
                    caps.perTransfer(), caps.daily(), BigDecimal.ZERO, caps.monthly(), BigDecimal.ZERO,
                    java.time.Instant.now());

            Decision decision = policy.evaluate(snapshot, bd("10000"));

            assertThat(decision.allowed())
                    .as("Basic 10000 must be allowed but breach was %s", decision.breach())
                    .isTrue();
        });
    }

    // ---- Preservation (basic-tier-30k-transfer-flag, Property 2): written to pass before and after the fix ----

    /** Fresh-account snapshot (nothing used today or this month) from the real caps for the tier. */
    private static LimitSnapshot freshSnapshot(LimitPolicy policy, LimitTier tier, boolean restricted) {
        LimitProperties.Caps caps = policy.caps(tier, restricted);
        return new LimitSnapshot(tier, restricted, caps.perTransfer(), caps.daily(), BigDecimal.ZERO,
                caps.monthly(), BigDecimal.ZERO, java.time.Instant.now());
    }

    /** 3.3: Basic 10001 and "real per-transfer cap + 1" both still breach PER_TRANSFER. */
    @Test
    void basicAboveRealPerTransferCapBreachesPerTransfer() throws IOException {
        withLimits(realLimitEntries()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LimitPolicy policy = new LimitPolicy(ctx.getBean(LimitProperties.class));
            LimitSnapshot fresh = freshSnapshot(policy, LimitTier.BASIC, false);

            Decision over10000 = policy.evaluate(fresh, bd("10001"));
            assertThat(over10000.allowed()).isFalse();
            assertThat(over10000.breach().capKind()).isEqualTo(CapKind.PER_TRANSFER);

            BigDecimal justOver = fresh.perTransferCap().add(BigDecimal.ONE);
            Decision overCap = policy.evaluate(fresh, justOver);
            assertThat(overCap.allowed()).isFalse();
            assertThat(overCap.breach().capKind()).isEqualTo(CapKind.PER_TRANSFER);

            assertThat(policy.evaluate(fresh, fresh.perTransferCap()).allowed()).isTrue();
        });
    }

    /** 3.3: Basic monthly cap stays 50000 and a monthly breach still reports MONTHLY. */
    @Test
    void basicMonthlyCapStaysAndMonthlyBreachStillBreachesMonthly() throws IOException {
        withLimits(realLimitEntries()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LimitPolicy policy = new LimitPolicy(ctx.getBean(LimitProperties.class));
            LimitProperties.Caps caps = policy.caps(LimitTier.BASIC, false);
            assertThat(caps.monthly()).isEqualByComparingTo(bd("50000"));

            // 100 left this month, nothing used today, amount within per-transfer and daily caps.
            LimitSnapshot nearMonthlyCap = new LimitSnapshot(LimitTier.BASIC, false,
                    caps.perTransfer(), caps.daily(), BigDecimal.ZERO,
                    caps.monthly(), caps.monthly().subtract(bd("100")), java.time.Instant.now());

            Decision breach = policy.evaluate(nearMonthlyCap, bd("101"));
            assertThat(breach.allowed()).isFalse();
            assertThat(breach.breach().capKind()).isEqualTo(CapKind.MONTHLY);
            assertThat(breach.breach().remaining()).isEqualByComparingTo(bd("100"));

            assertThat(policy.evaluate(nearMonthlyCap, bd("100")).allowed()).isTrue();
        });
    }

    /** 3.3: Fully Verified caps stay 50000 / 100000 / 500000 and 50001 breaches PER_TRANSFER. */
    @Test
    void fullyVerifiedCapsStayAndOver50000BreachesPerTransfer() throws IOException {
        withLimits(realLimitEntries()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LimitPolicy policy = new LimitPolicy(ctx.getBean(LimitProperties.class));
            LimitProperties.Caps caps = policy.caps(LimitTier.FULLY_VERIFIED, false);
            assertThat(caps.perTransfer()).isEqualByComparingTo(bd("50000"));
            assertThat(caps.daily()).isEqualByComparingTo(bd("100000"));
            assertThat(caps.monthly()).isEqualByComparingTo(bd("500000"));

            LimitSnapshot fresh = freshSnapshot(policy, LimitTier.FULLY_VERIFIED, false);
            assertThat(policy.evaluate(fresh, bd("50000")).allowed()).isTrue();

            Decision over = policy.evaluate(fresh, bd("50001"));
            assertThat(over.allowed()).isFalse();
            assertThat(over.breach().capKind()).isEqualTo(CapKind.PER_TRANSFER);
        });
    }

    /** 3.3: LOCKED and SUSPENDED users get all-zero caps and every transfer is rejected, for both tiers. */
    @Test
    void lockedAndSuspendedStillRejectEveryTransfer() throws IOException {
        withLimits(realLimitEntries()).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LimitPolicy policy = new LimitPolicy(ctx.getBean(LimitProperties.class));

            for (UserStatus user : new UserStatus[] {UserStatus.LOCKED, UserStatus.SUSPENDED}) {
                for (KycStatus kyc : new KycStatus[] {KycStatus.PENDING, KycStatus.VERIFIED}) {
                    LimitPolicy.Resolution r = policy.resolve(kyc, user);
                    String label = kyc + "/" + user;
                    assertThat(r.restricted()).as(label).isTrue();

                    LimitProperties.Caps caps = policy.caps(r.tier(), r.restricted());
                    assertThat(caps.perTransfer()).as(label).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(caps.daily()).as(label).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(caps.monthly()).as(label).isEqualByComparingTo(BigDecimal.ZERO);

                    for (String amount : new String[] {"1", "30000", "30001"}) {
                        Decision d = policy.evaluate(freshSnapshot(policy, r.tier(), true), bd(amount));
                        assertThat(d.allowed()).as("%s %s", label, amount).isFalse();
                        assertThat(d.breach().restricted()).as("%s %s", label, amount).isTrue();
                    }
                }
            }
        });
    }

    @Test
    void zeroIsAcceptedAsACapValue() throws IOException {
        Map<String, Object> limits = realLimitEntries();
        limits.put("limits.basic.daily", "0");
        withLimits(limits).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(LimitProperties.class).basic().daily()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    static Stream<Arguments> invalidCases() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (String key : KEYS) {
            b.add(Arguments.of(key, "missing", null));
            b.add(Arguments.of(key, "non-numeric", "abc"));
            b.add(Arguments.of(key, "negative", "-1"));
        }
        return b.build();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("invalidCases")
    void invalidValueFailsStartupNamingTheProperty(String key, String kind, String badValue) throws IOException {
        Map<String, Object> limits = realLimitEntries();
        if (badValue == null) {
            limits.remove(key);
        } else {
            limits.put(key, badValue);
        }

        withLimits(limits).run(ctx -> {
            Throwable failure = ctx.getStartupFailure();
            assertThat(failure).as("context must fail to start for %s %s", key, kind).isNotNull();
            assertThat(namesProperty(causeChainMessages(failure), key))
                    .as("failure must name %s (%s) but was:%n%s", key, kind, causeChainMessages(failure))
                    .isTrue();
        });
    }

    /**
     * True if the failure text identifies the property.
     *
     * Spring's relaxed binding treats {@code limits.basic.per-transfer} and {@code limits.basic.perTransfer}
     * as the same property. Binding errors (non-numeric) report the kebab key, but @Validated constraint
     * errors (missing/negative) report the Java names: object {@code limits.basic} plus field
     * {@code perTransfer}. Any of these forms identifies the tier and the field, so all are accepted.
     */
    private static boolean namesProperty(String text, String key) {
        String tier = key.substring(PREFIX.length(), key.lastIndexOf('.'));
        String field = key.substring(key.lastIndexOf('.') + 1);
        String camelKey = PREFIX + camel(tier) + "." + camel(field);
        if (text.contains(key) || text.contains(camelKey)) {
            return true;
        }
        boolean tierNamed = text.contains(PREFIX + tier) || text.contains(PREFIX + camel(tier));
        return tierNamed && text.contains(camel(field));
    }

    private static String camel(String kebab) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char ch : kebab.toCharArray()) {
            if (ch == '-') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return sb.toString();
    }

    private static String causeChainMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
