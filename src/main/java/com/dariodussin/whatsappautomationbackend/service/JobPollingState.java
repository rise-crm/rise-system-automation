package com.dariodussin.whatsappautomationbackend.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobPollingState {

    static final String KEY = "jobs:polling:enabled";

    private static final DefaultRedisScript<Long> TOGGLE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == 'false' then
                redis.call('SET', KEYS[1], 'true')
                return 1
            end
            redis.call('SET', KEYS[1], 'false')
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public JobPollingState(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isEnabled() {
        try {
            return !"false".equals(redis.opsForValue().get(KEY));
        } catch (RuntimeException e) {
            System.err.printf("[JOBS] Polling flag unavailable | error=%s%n", e.getClass().getSimpleName());
            return true;
        }
    }

    public boolean toggle() {
        Long enabled = redis.execute(TOGGLE_SCRIPT, List.of(KEY));
        return enabled != null && enabled == 1L;
    }
}
