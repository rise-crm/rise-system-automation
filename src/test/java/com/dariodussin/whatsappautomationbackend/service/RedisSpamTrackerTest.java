package com.dariodussin.whatsappautomationbackend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class RedisSpamTrackerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T21:00:00Z");
    private static final String GROUP = "120363000000000000@g.us";
    private static final String CONTACT = "5511999887766@s.whatsapp.net";
    private static final String MESSAGE_ID = "MSG10";

    @Mock
    private StringRedisTemplate redis;

    private RedisSpamTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new RedisSpamTracker(redis, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void scriptReceivesKeyMessageIdAndSixtySecondCutoff() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(10L);

        assertTrue(tracker.record(GROUP, CONTACT, MESSAGE_ID));

        ArgumentCaptor<RedisScript<Long>> script = ArgumentCaptor.forClass(RedisScript.class);
        long now = NOW.toEpochMilli();
        verify(redis).execute(
                script.capture(),
                eq(List.of(RedisSpamTracker.key(GROUP, CONTACT))),
                eq(String.valueOf(now - RedisSpamTracker.WINDOW_MILLIS)),
                eq(String.valueOf(now)),
                eq(MESSAGE_ID),
                eq(String.valueOf(RedisSpamTracker.WINDOW_MILLIS)));

        String source = script.getValue().getScriptAsString();
        assertTrue(source.contains("ZREMRANGEBYSCORE"));
        assertTrue(source.contains("ZADD"));
        assertTrue(source.contains("NX"));
        assertTrue(source.contains("PEXPIRE"));
        assertTrue(source.contains("ZCARD"));
    }

    @Test
    void countBelowThresholdIsNotSpam() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(9L);

        assertFalse(tracker.record(GROUP, CONTACT, MESSAGE_ID));
    }

    @Test
    void redisFailureIsNotSpam() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertFalse(tracker.record(GROUP, CONTACT, MESSAGE_ID));
    }
}
