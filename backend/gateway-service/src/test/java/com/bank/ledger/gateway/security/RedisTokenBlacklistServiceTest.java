package com.bank.ledger.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisTokenBlacklistServiceTest {

    @Test
    @DisplayName("Revocation is looked up under blacklist:jti:<jti>, the key account-service writes")
    void looksUpJtiKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.hasKey("blacklist:jti:abc")).thenReturn(true);
        RedisTokenBlacklistService service = new RedisTokenBlacklistService(redis);

        assertThat(service.isBlacklisted("abc")).isTrue();
        assertThat(service.isBlacklisted("other")).isFalse();
        assertThat(service.isBlacklisted(null)).isFalse();
    }
}
