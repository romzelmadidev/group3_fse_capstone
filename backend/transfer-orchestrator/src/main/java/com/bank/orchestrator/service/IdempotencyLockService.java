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

    public void releaseLock(String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            redisTemplate.delete(IDEMP_PREFIX + idempotencyKey);
        }
    }
}
