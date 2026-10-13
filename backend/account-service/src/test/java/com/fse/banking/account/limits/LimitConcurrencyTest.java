package com.fse.banking.account.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

import com.fse.banking.account.model.AccountEntity;
import com.fse.banking.account.model.UserEntity;
import com.fse.banking.account.repository.AccountRepository;
import com.fse.banking.account.repository.UserRepository;
import com.fse.banking.common.enums.KycStatus;
import com.fse.banking.common.enums.UserStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Feature: kyc-tiered-transfer-limits, Property 9: Concurrent transfers stay within the cap.
 * For any remaining amount, any set of transfer amounts and any interleaving of concurrent requests from
 * one customer, the sum of the amounts accepted and posted never exceeds the remaining amount of any cap
 * (and no accepted amount exceeds the per-transfer cap). Requests that fail with LimitUnavailableException
 * (lock timeout) post nothing.
 *
 * <p>Orchestrator flow simulated per request: evaluate -> if allowed, post (increment CBS usage) -> release.
 *
 * <p>Validates: Requirements 4.11
 */
class LimitConcurrencyTest {

    private static final int ITERATIONS = 150;
    private static final String USER_ID = "USR-CONC";
    private static final String ACCOUNT_ID = "ACC-CONC";

    /** In-memory stand-in for the CBS: usage grows only when a transfer is "posted". */
    private static final class FakeCbs extends CbsUsageClient {
        final AtomicReference<BigDecimal> dailyUsed;
        final AtomicReference<BigDecimal> monthlyUsed;

        FakeCbs(BigDecimal daily, BigDecimal monthly) {
            super("http://unused", new org.springframework.web.client.RestTemplate());
            this.dailyUsed = new AtomicReference<>(daily);
            this.monthlyUsed = new AtomicReference<>(monthly);
        }

        @Override
        public Usage fetchOutgoing(List<String> accountIds, LimitPolicy.Windows w) {
            // Widen the check-to-post race window so an unlocked implementation would be caught.
            Thread.yield();
            return new Usage(dailyUsed.get(), monthlyUsed.get());
        }

        void post(BigDecimal amount) {
            Thread.yield();
            dailyUsed.updateAndGet(v -> v.add(amount));
            monthlyUsed.updateAndGet(v -> v.add(amount));
        }
    }

    /** ConcurrentHashMap-backed StringRedisTemplate: SET NX PX and the compare-and-delete Lua script. */
    private static final class MapRedis extends StringRedisTemplate {
        final Map<String, String> store = new ConcurrentHashMap<>();
        final AtomicInteger badTtl = new AtomicInteger();
        private final ValueOperations<String, String> ops;

        @SuppressWarnings("unchecked")
        MapRedis() {
            ops = mock(ValueOperations.class, withSettings().stubOnly());
            when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
                if (!Duration.ofMillis(LimitService.LOCK_TTL_MS).equals(inv.getArgument(2))) {
                    badTtl.incrementAndGet();
                }
                return store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null;
            });
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return ops;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            // compare-and-delete: del only when the key still holds the caller's token
            return (T) Long.valueOf(store.remove(keys.get(0), String.valueOf(args[0])) ? 1L : 0L);
        }
    }

    @Test
    void concurrentTransfersNeverExceedTheRemainingCapOrPerTransferRule() throws Exception {
        Random rnd = new Random(0);
        int totalAccepted = 0;
        int totalLockTimeouts = 0;
        int totalRejected = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            BigDecimal perTransferCap = money(rnd, 50, 600);
            BigDecimal dailyRemaining0 = money(rnd, 0, 1000);
            BigDecimal monthlyRemaining0 = money(rnd, 0, 1500);
            BigDecimal dailyUsed0 = money(rnd, 0, 5000);
            BigDecimal monthlyUsed0 = money(rnd, 0, 20000);
            BigDecimal dailyCap = dailyUsed0.add(dailyRemaining0);
            BigDecimal monthlyCap = monthlyUsed0.add(monthlyRemaining0);
            BigDecimal bindingRemaining = dailyRemaining0.min(monthlyRemaining0);

            int threads = 2 + rnd.nextInt(7);
            int requests = 3 + rnd.nextInt(10);
            List<BigDecimal> amounts = new ArrayList<>();
            for (int r = 0; r < requests; r++) {
                int pick = rnd.nextInt(8);
                BigDecimal amount;
                if (pick == 0) {
                    amount = perTransferCap;                  // boundary: equals per-transfer cap
                } else if (pick == 1) {
                    amount = bindingRemaining.max(new BigDecimal("0.01")); // boundary: equals remaining
                } else if (pick == 2) {
                    amount = perTransferCap.add(money(rnd, 1, 100)); // above per-transfer cap
                } else {
                    amount = money(rnd, 1, 400);
                }
                amounts.add(amount);
            }

            LimitProperties.Caps caps = new LimitProperties.Caps(perTransferCap, dailyCap, monthlyCap);
            LimitPolicy policy = new LimitPolicy(new LimitProperties(caps, caps));
            FakeCbs cbs = new FakeCbs(dailyUsed0, monthlyUsed0);
            MapRedis redis = new MapRedis();
            LimitService service = newService(policy, cbs, redis);

            AtomicInteger inLock = new AtomicInteger();
            AtomicInteger maxInLock = new AtomicInteger();
            AtomicInteger lockTimeouts = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            List<BigDecimal> accepted = new ArrayList<>();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int r = 0; r < requests; r++) {
                BigDecimal amount = amounts.get(r);
                String txId = "TXN-" + i + "-" + r;
                Callable<Void> task = () -> {
                    start.await();
                    LimitService.Evaluation ev;
                    try {
                        ev = service.evaluate(txId, ACCOUNT_ID, amount);
                    } catch (LimitUnavailableException e) {
                        lockTimeouts.incrementAndGet(); // nothing posted
                        return null;
                    }
                    if (!ev.allowed()) {
                        rejected.incrementAndGet();
                        return null;
                    }
                    try {
                        int now = inLock.incrementAndGet();
                        maxInLock.accumulateAndGet(now, Math::max);
                        cbs.post(amount);
                        synchronized (accepted) {
                            accepted.add(amount);
                        }
                    } finally {
                        inLock.decrementAndGet();
                        service.release(ev.lockToken());
                    }
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            BigDecimal sum = accepted.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            String ctx = "iteration=" + i + " perTransferCap=" + perTransferCap + " dailyRemaining0=" + dailyRemaining0
                    + " monthlyRemaining0=" + monthlyRemaining0 + " amounts=" + amounts + " accepted=" + accepted;

            assertTrue(sum.compareTo(dailyRemaining0) <= 0, "daily cap exceeded: " + ctx);
            assertTrue(sum.compareTo(monthlyRemaining0) <= 0, "monthly cap exceeded: " + ctx);
            for (BigDecimal a : accepted) {
                assertTrue(a.compareTo(perTransferCap) <= 0, "per-transfer rule violated by " + a + ": " + ctx);
            }
            assertEquals(0, dailyUsed0.add(sum).compareTo(cbs.dailyUsed.get()), ctx);
            assertEquals(0, monthlyUsed0.add(sum).compareTo(cbs.monthlyUsed.get()), ctx);
            assertEquals(requests, accepted.size() + rejected.get() + lockTimeouts.get(), ctx);
            assertTrue(maxInLock.get() <= 1, "more than one holder inside the customer lock: " + ctx);
            assertEquals(0, redis.badTtl.get(), "lock must be set with the 30s TTL");
            assertTrue(redis.store.isEmpty(), "every lock must be released: " + ctx);

            totalAccepted += accepted.size();
            totalRejected += rejected.get();
            totalLockTimeouts += lockTimeouts.get();
        }

        // Sanity: the generator exercised both accept and reject paths.
        assertTrue(totalAccepted > 0, "no transfer was ever accepted");
        assertTrue(totalRejected > 0, "no transfer was ever rejected");
        System.out.println("LimitConcurrencyTest: accepted=" + totalAccepted + " rejected=" + totalRejected
                + " lockTimeouts=" + totalLockTimeouts);
    }

    private static LimitService newService(LimitPolicy policy, CbsUsageClient cbs, StringRedisTemplate redis) {
        UserEntity user = UserEntity.builder().userId(USER_ID).kycStatus(KycStatus.PENDING)
                .status(UserStatus.ACTIVE).build();
        AccountEntity account = AccountEntity.builder().accountId(ACCOUNT_ID).userId(USER_ID)
                .accountNumber("1000-2000-3001").build();

        UserRepository users = mock(UserRepository.class, withSettings().stubOnly());
        AccountRepository accounts = mock(AccountRepository.class, withSettings().stubOnly());
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(accounts.findByUserId(USER_ID)).thenReturn(List.of(account));

        // Short poll/wait so lock timeouts are possible but the test stays fast.
        return new LimitService(users, accounts, cbs, policy, redis, 1, 300);
    }

    private static BigDecimal money(Random rnd, int minUnits, int maxUnits) {
        return BigDecimal.valueOf(minUnits + rnd.nextInt(maxUnits - minUnits + 1)).setScale(2);
    }
}
