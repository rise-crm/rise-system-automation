package com.dariodussin.whatsappautomationbackend.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

@Service
public class RedisJobSendProgress implements JobSendProgress {

    static final Duration TTL = Duration.ofDays(2);
    static final String KEY_PREFIX = "job:sent:";

    private final StringRedisTemplate redis;

    public RedisJobSendProgress(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Set<String> alreadySent(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Set.of();
        }
        try {
            Set<String> members = redis.opsForSet().members(key(jobId));
            return members == null ? Set.of() : Set.copyOf(members);
        } catch (RuntimeException e) {
            System.err.printf("[JOBS] Send progress read failed | job=%s | error=%s%n",
                    jobId, e.getClass().getSimpleName());
            return Set.of();
        }
    }

    @Override
    public void markSent(String jobId, String groupId) {
        if (jobId == null || jobId.isBlank() || groupId == null || groupId.isBlank()) {
            return;
        }
        try {
            String redisKey = key(jobId);
            redis.opsForSet().add(redisKey, groupId);
            redis.expire(redisKey, TTL);
        } catch (RuntimeException e) {
            System.err.printf("[JOBS] Send progress write failed | job=%s | error=%s%n",
                    jobId, e.getClass().getSimpleName());
        }
    }

    @Override
    public void clear(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        try {
            redis.delete(key(jobId));
        } catch (RuntimeException e) {
            System.err.printf("[JOBS] Send progress clear failed | job=%s | error=%s%n",
                    jobId, e.getClass().getSimpleName());
        }
    }

    static String key(String jobId) {
        return KEY_PREFIX + jobId;
    }
}
