package com.bank.ledger.gateway.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RedisTokenBlacklistService {

    private final StringRedisTemplate redisTemplate;

    /** Same key scheme account-service writes in RedisSessionStore.blacklistToken. */
    private static final String BLACKLIST_PREFIX = "blacklist:jti:";

    public boolean isBlacklisted(String jti) {
        return jti != null && Boolean.TRUE.equals(
                redisTemplate.hasKey(BLACKLIST_PREFIX + jti)
        );
    }
}