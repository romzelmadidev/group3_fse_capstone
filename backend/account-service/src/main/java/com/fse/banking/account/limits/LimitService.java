package com.fse.banking.account.limits;

import com.fse.banking.account.model.AccountEntity;
import com.fse.banking.account.model.UserEntity;
import com.fse.banking.account.repository.AccountRepository;
import com.fse.banking.account.repository.UserRepository;
import com.fse.banking.common.enums.KycStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Resolves a customer's limits and enforces them under a per-customer Redis lock.
 *
 * <p>Lock token format: {@code "<userId>:<uuid>"}. {@link #release(String)} only receives the token,
 * so the lock key ({@code limits:lock:<userId>}) is recovered from the part before the last ':'.
 * The release script deletes the key only if it still holds exactly this token.
 */
@Slf4j
@Service
public class LimitService {

    static final String LOCK_KEY_PREFIX = "limits:lock:";
    static final long LOCK_TTL_MS = 30_000;
    static final long DEFAULT_POLL_INTERVAL_MS = 50;
    static final long DEFAULT_LOCK_WAIT_MS = 2_000;

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /** Result of {@link #evaluate}. {@code lockToken} is set only when allowed (the lock is then held). */
    public record Evaluation(boolean allowed, Breach breach, String lockToken) {
    }

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final CbsUsageClient cbsUsageClient;
    private final LimitPolicy policy;
    private final StringRedisTemplate redis;
    private final long pollIntervalMs;
    private final long lockWaitMs;

    @Autowired
    public LimitService(UserRepository userRepository, AccountRepository accountRepository,
            CbsUsageClient cbsUsageClient, LimitPolicy policy, StringRedisTemplate redis) {
        this(userRepository, accountRepository, cbsUsageClient, policy, redis,
                DEFAULT_POLL_INTERVAL_MS, DEFAULT_LOCK_WAIT_MS);
    }

    /** Lets tests shorten the lock polling interval and wait. */
    public LimitService(UserRepository userRepository, AccountRepository accountRepository,
            CbsUsageClient cbsUsageClient, LimitPolicy policy, StringRedisTemplate redis,
            long pollIntervalMs, long lockWaitMs) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.cbsUsageClient = cbsUsageClient;
        this.policy = policy;
        this.redis = redis;
        this.pollIntervalMs = pollIntervalMs;
        this.lockWaitMs = lockWaitMs;
    }

    /** A snapshot plus what the read endpoint also needs: the user's KYC status and the window reset times. */
    public record View(LimitSnapshot snapshot, KycStatus kycStatus, LimitPolicy.Windows windows) {
    }

    /** Builds the customer's current snapshot (tier, caps, usage). */
    public LimitSnapshot snapshotFor(String userId) {
        return viewFor(userId).snapshot();
    }

    /** Snapshot, KYC status and windows for the limits read endpoint. */
    public View viewFor(String userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new LimitUnavailableException("User not found for limits: " + userId));
        Instant now = Instant.now();
        LimitPolicy.Resolution resolution = policy.resolve(user.getKycStatus(), user.getStatus());
        LimitProperties.Caps caps = policy.caps(resolution.tier(), resolution.restricted());
        LimitPolicy.Windows windows = policy.windows(now);

        BigDecimal dailyUsed = BigDecimal.ZERO;
        BigDecimal monthlyUsed = BigDecimal.ZERO;
        List<String> accountIds = sourceIdentifiers(accountRepository.findByUserId(userId));
        if (!accountIds.isEmpty()) {
            CbsUsageClient.Usage usage = cbsUsageClient.fetchOutgoing(accountIds, windows);
            dailyUsed = usage.dailyUsed();
            monthlyUsed = usage.monthlyUsed();
        }
        LimitSnapshot snapshot = new LimitSnapshot(resolution.tier(), resolution.restricted(), caps.perTransfer(),
                caps.daily(), dailyUsed, caps.monthly(), monthlyUsed, now);
        return new View(snapshot, user.getKycStatus(), windows);
    }

    /**
     * Tier and restricted flag for the account's owner, via the same {@code resolve} call as the limit check.
     * Read-only: no customer lock and no CBS call.
     *
     * @param sourceAccountId account id or account number
     * @throws LimitUnavailableException unknown account or user
     */
    public LimitPolicy.Resolution tierFor(String sourceAccountId) {
        String userId = findAccount(sourceAccountId).getUserId();
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new LimitUnavailableException("User not found for limits: " + userId));
        return policy.resolve(user.getKycStatus(), user.getStatus());
    }

    /**
     * Checks the transfer against the customer's caps while holding the customer lock.
     * Breach: lock released before returning. Allowed: lock stays held; caller must {@link #release} the token.
     *
     * @throws LimitUnavailableException unknown account/user, lock timeout or CBS usage failure
     */
    public Evaluation evaluate(String transactionId, String sourceAccountId, BigDecimal amount) {
        String userId = findAccount(sourceAccountId).getUserId();
        String key = LOCK_KEY_PREFIX + userId;
        String token = userId + ":" + UUID.randomUUID();
        acquire(key, token);

        boolean keepLock = false;
        try {
            Decision decision = policy.evaluate(snapshotFor(userId), amount);
            if (decision.allowed()) {
                keepLock = true;
                return new Evaluation(true, null, token);
            }
            log.info("Transfer {} rejected by limits: {}", transactionId, decision.breach().capKind());
            return new Evaluation(false, decision.breach(), null);
        } catch (LimitUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LimitUnavailableException("Limit evaluation failed: " + e.getMessage(), e);
        } finally {
            if (!keepLock) {
                unlock(key, token);
            }
        }
    }

    /** Releases the lock named by the token if it still holds it. Never throws: the lock TTL is the backstop. */
    public void release(String token) {
        int sep = token == null ? -1 : token.lastIndexOf(':');
        if (sep <= 0) {
            log.warn("Ignoring malformed limit lock token");
            return;
        }
        unlock(LOCK_KEY_PREFIX + token.substring(0, sep), token);
    }

    private AccountEntity findAccount(String sourceAccountId) {
        if (sourceAccountId == null || sourceAccountId.isBlank()) {
            throw new LimitUnavailableException("Source account is missing");
        }
        return accountRepository.findById(sourceAccountId)
                .or(() -> accountRepository.findByAccountNumber(sourceAccountId))
                .orElseThrow(() -> new LimitUnavailableException("Unknown source account: " + sourceAccountId));
    }

    /** CBS may record either the account id or the account number as the source, so send both. */
    private static List<String> sourceIdentifiers(List<AccountEntity> accounts) {
        Set<String> ids = new LinkedHashSet<>();
        for (AccountEntity a : accounts) {
            ids.add(a.getAccountId());
            if (a.getAccountNumber() != null) {
                ids.add(a.getAccountNumber());
            }
        }
        ids.removeIf(Objects::isNull);
        return List.copyOf(ids);
    }

    /** SET key token NX PX 30000, polled every pollIntervalMs for up to lockWaitMs. */
    private void acquire(String key, String token) {
        long deadline = System.nanoTime() + Duration.ofMillis(lockWaitMs).toNanos();
        try {
            while (true) {
                if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, Duration.ofMillis(LOCK_TTL_MS)))) {
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    throw new LimitUnavailableException("Timed out waiting for the customer limit lock");
                }
                Thread.sleep(pollIntervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LimitUnavailableException("Interrupted while waiting for the customer limit lock", e);
        } catch (LimitUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LimitUnavailableException("Limit lock unavailable: " + e.getMessage(), e);
        }
    }

    private void unlock(String key, String token) {
        try {
            redis.execute(COMPARE_AND_DELETE, List.of(key), token);
        } catch (RuntimeException e) {
            log.warn("Failed to release limit lock {}; it will expire after {} ms: {}", key, LOCK_TTL_MS, e.getMessage());
        }
    }
}
