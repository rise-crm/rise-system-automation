package com.dariodussin.whatsappautomationbackend.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@Service
public class RedisSpamTracker implements SpamTracker {

    static final int THRESHOLD = 10;
    static final long WINDOW_MILLIS = 60_000L;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisSpamTracker(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public boolean record(String groupJid, String contactJid, String messageId) {
        if (groupJid == null || groupJid.isBlank()
                || contactJid == null || contactJid.isBlank()
                || messageId == null || messageId.isBlank()) {
            return false;
        }
        try {
            long now = clock.millis();
            Long count = redis.execute(
                    SCRIPT,
                    List.of(key(groupJid, contactJid)),
                    String.valueOf(now - WINDOW_MILLIS),
                    String.valueOf(now),
                    messageId,
                    String.valueOf(WINDOW_MILLIS));
            return count != null && count >= THRESHOLD;
        } catch (RuntimeException e) {
            System.err.printf("[WEBHOOK] Spam window unavailable: %s%n", e.getClass().getSimpleName());
            return false;
        }
    }

    static String key(String groupJid, String contactJid) {
        return "spam:" + groupJid + ":" + contactJid;
    }
}
