package com.dariodussin.whatsappautomationbackend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRemovalLockTest {

    private static final String GROUP = "120363000000000000@g.us";
    private static final String CONTACT = "5511999887766@s.whatsapp.net";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    private RedisRemovalLock lock;

    @BeforeEach
    void setUp() {
        lock = new RedisRemovalLock(redis);
    }

    @Test
    void firstAcquireSetsKeyWithTtl() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertTrue(lock.tryAcquire(GROUP, CONTACT));

        verify(values).setIfAbsent(
                RedisRemovalLock.key(GROUP, CONTACT), "1", Duration.ofMillis(RedisRemovalLock.LOCK_MILLIS));
    }

    @Test
    void existingKeyIsNotAcquired() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        assertFalse(lock.tryAcquire(GROUP, CONTACT));
    }

    @Test
    void redisFailureAllowsRemoval() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(lock.tryAcquire(GROUP, CONTACT));
    }

    @Test
    void releaseDeletesKeyAndSwallowsFailure() {
        when(redis.delete(RedisRemovalLock.key(GROUP, CONTACT)))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> lock.release(GROUP, CONTACT));
        verify(redis).delete(RedisRemovalLock.key(GROUP, CONTACT));
    }
}
