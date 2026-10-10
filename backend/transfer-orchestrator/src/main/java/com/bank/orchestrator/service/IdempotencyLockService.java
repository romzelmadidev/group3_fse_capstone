package com.bank.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdempotencyLockService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyLockService.class);
    private static final String IDEMP_PREFIX = "tx:idemp:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;

    public IdempotencyLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean acquireLock(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }

        String key = IDEMP_PREFIX + idempotencyKey;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "PROCESSING", LOCK_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            log.info("Acquired idempotency lock for key: {}", idempotencyKey);
            return true;
        }

        log.warn("Duplicate request detected for idempotency key: {}", idempotencyKey);
        return false;
    }

    private static final String IDEMP_RESP_PREFIX = "tx:idemp:resp:";
    private static final Duration RESP_TTL = Duration.ofHours(24);

    public void cacheResponse(String idempotencyKey, String responseJson) {
        if (idempotencyKey != null && !idempotencyKey.isBlank() && responseJson != null) {
            redisTemplate.opsForValue().set(IDEMP_RESP_PREFIX + idempotencyKey, responseJson, RESP_TTL);
        }
    }

    public java.util.Optional<String> getCachedResponse(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return java.util.Optional.empty();
        }
        String val = redisTemplate.opsForValue().get(IDEMP_RESP_PREFIX + idempotencyKey);
        return java.util.Optional.ofNullable(val);
    }

    public void evictBalanceCache(String sourceAccountId, String destinationAccountId) {
        try {
            java.util.List<String> keys = new java.util.ArrayList<>();
            if (sourceAccountId != null && !sourceAccountId.isBlank()) {
                keys.add("account:balance:" + sourceAccountId);
            }
            if (destinationAccountId != null && !destinationAccountId.isBlank()) {
                keys.add("account:balance:" + destinationAccountId);
            }
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Evicted balance cache for accounts: {}", keys);
            }
        } catch (Exception e) {
            log.warn("Failed to evict balance cache: {}", e.getMessage());
        }
    }

    public void releaseLock(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            redisTemplate.delete(IDEMP_PREFIX + idempotencyKey);
        }
    }
}
