package com.fse.banking.account.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSessionStoreTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ZSetOperations<String, String> zset;
    @Mock private ValueOperations<String, String> values;
    @Mock private SetOperations<String, String> sets;

    private RedisSessionStore store;
    private static final String KEY = "auth:user-sessions-z:USR-1";

    @BeforeEach
    void setUp() {
        store = new RedisSessionStore(stringRedisTemplate, redisTemplate, new ObjectMapper());
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zset);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(values);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(sets);
    }

    @Test
    @DisplayName("At the session limit the oldest live token is evicted, blacklisted for its real remaining life, and its refresh family purged")
    void evictsOldestLiveSession() {
        long now = Instant.now().getEpochSecond();
        when(zset.size(KEY)).thenReturn(3L);
        when(zset.popMin(KEY)).thenReturn(new DefaultTypedTuple<>("old-jti", (double) (now + 300)));
        when(values.get("auth:jti-session:old-jti")).thenReturn("SESS-OLD");

        store.registerSessionToken("USR-1", "SESS-NEW", "new-jti", now + 900, 3);

        verify(zset).removeRangeByScore(eq(KEY), anyDouble(), anyDouble());
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("blacklist:jti:old-jti"), eq("REVOKED"), ttl.capture());
        assertThat(ttl.getValue().getSeconds()).isBetween(290L, 300L);
        verify(stringRedisTemplate).delete("token_family:SESS-OLD");
        verify(zset).add(KEY, "new-jti", (double) (now + 900));
    }

    @Test
    @DisplayName("Below the limit nobody is evicted")
    void underLimitEvictsNobody() {
        long now = Instant.now().getEpochSecond();
        when(zset.size(KEY)).thenReturn(2L);

        store.registerSessionToken("USR-1", "SESS-NEW", "new-jti", now + 900, 3);

        verify(zset, never()).popMin(anyString());
        verify(values, never()).set(eq("blacklist:jti:new-jti"), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        verify(zset).add(KEY, "new-jti", (double) (now + 900));
    }

    @Test
    @DisplayName("A refreshed session swaps its old token for the new one and keeps a single slot")
    void rotateKeepsOneSlot() {
        long now = Instant.now().getEpochSecond();
        when(values.get("auth:session-jti:SESS-1")).thenReturn("old-jti");

        store.rotateSessionToken("USR-1", "SESS-1", "new-jti", now + 900);

        verify(zset).remove(KEY, "old-jti");
        verify(zset).add(KEY, "new-jti", (double) (now + 900));
    }

    @Test
    @DisplayName("Lockout window: counting starts the TTL once, and lock seconds are 0 when no lock exists")
    void lockoutCounters() {
        when(values.increment("auth:login-fail:USR-1")).thenReturn(1L);
        when(stringRedisTemplate.getExpire("auth:login-lock:USR-1")).thenReturn(-2L);

        assertThat(store.recordFailedLogin("USR-1", Duration.ofMinutes(15))).isEqualTo(1L);
        verify(stringRedisTemplate).expire("auth:login-fail:USR-1", Duration.ofMinutes(15));
        assertThat(store.getLoginLockSeconds("USR-1")).isZero();
    }
}
