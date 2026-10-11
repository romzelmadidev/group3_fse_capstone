package com.bank.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class CoolOffService {

    private static final Logger log = LoggerFactory.getLogger(CoolOffService.class);
    private static final String COOLOFF_PREFIX = "tx:cooloff:";
    private static final Duration COOLOFF_DURATION = Duration.ofSeconds(600); // 10 minutes

    private final StringRedisTemplate redisTemplate;

    public CoolOffService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void putInCoolOff(String transactionId, String payloadJson) {
        String key = COOLOFF_PREFIX + transactionId;
        redisTemplate.opsForValue().set(key, payloadJson, COOLOFF_DURATION);
        log.info("Transaction {} entered 10-minute anti-scam cooling-off period", transactionId);
    }

    public boolean isInCoolOff(String transactionId) {
        String key = COOLOFF_PREFIX + transactionId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    public Long getRemainingCoolOffSeconds(String transactionId) {
        String key = COOLOFF_PREFIX + transactionId;
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return (ttl != null && ttl > 0) ? ttl : 0L;
    }

    public String getCoolOffPayload(String transactionId) {
        String key = COOLOFF_PREFIX + transactionId;
        return redisTemplate.opsForValue().get(key);
    }

    public boolean cancelCoolOff(String transactionId) {
        String key = COOLOFF_PREFIX + transactionId;
        Boolean deleted = redisTemplate.delete(key);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("Transaction {} cooling-off successfully cancelled by user", transactionId);
            return true;
        }
        return false;
    }
}
