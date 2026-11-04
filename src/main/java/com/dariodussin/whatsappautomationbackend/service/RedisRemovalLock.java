package com.dariodussin.whatsappautomationbackend.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RedisRemovalLock implements RemovalLock {

    static final long LOCK_MILLIS = 300_000L;

    private final StringRedisTemplate redis;

    public RedisRemovalLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String groupJid, String contactJid) {
        try {
            Boolean acquired = redis.opsForValue()
                    .setIfAbsent(key(groupJid, contactJid), "1", Duration.ofMillis(LOCK_MILLIS));
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException e) {
            System.err.printf("[WEBHOOK] Removal lock unavailable: %s%n", e.getClass().getSimpleName());
            return true;
        }
    }

    @Override
    public void release(String groupJid, String contactJid) {
        try {
            redis.delete(key(groupJid, contactJid));
        } catch (RuntimeException e) {
            System.err.printf("[WEBHOOK] Removal lock release failed: %s%n", e.getClass().getSimpleName());
        }
    }

    static String key(String groupJid, String contactJid) {
        return "ban:" + groupJid + ":" + contactJid;
    }
}
