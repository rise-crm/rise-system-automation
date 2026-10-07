package com.dariodussin.whatsappautomationbackend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookRateLimiterTest {

    @Test
    void allowsBurstThenRejectsUntilRefill() {
        long[] now = {0};
        WebhookRateLimiter limiter = new WebhookRateLimiter(10, 2, () -> now[0]);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        now[0] += 100_000_000L;
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void refillDoesNotExceedBurst() {
        long[] now = {0};
        WebhookRateLimiter limiter = new WebhookRateLimiter(10, 2, () -> now[0]);

        assertTrue(limiter.tryAcquire());
        now[0] += 10_000_000_000L;

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }
}
