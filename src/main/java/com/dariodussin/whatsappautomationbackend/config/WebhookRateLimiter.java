package com.dariodussin.whatsappautomationbackend.config;

import java.util.function.LongSupplier;

public final class WebhookRateLimiter {

    private final double permitsPerSecond;
    private final double capacity;
    private final LongSupplier nanoTime;

    private double available;
    private long lastRefillNanos;

    public WebhookRateLimiter(double permitsPerSecond, double burst, LongSupplier nanoTime) {
        if (permitsPerSecond <= 0 || burst < 1) {
            throw new IllegalArgumentException(
                    "webhook rate limit requires a positive rate and a burst of at least 1");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.capacity = burst;
        this.nanoTime = nanoTime;
        this.available = burst;
        this.lastRefillNanos = nanoTime.getAsLong();
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (available < 1.0) {
            return false;
        }
        available -= 1.0;
        return true;
    }

    public synchronized int retryAfterSeconds() {
        refill();
        if (available >= 1.0) {
            return 0;
        }
        double missing = 1.0 - available;
        return (int) Math.max(1, Math.ceil(missing / permitsPerSecond));
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        double added = (elapsed / 1_000_000_000.0) * permitsPerSecond;
        available = Math.min(capacity, available + added);
        lastRefillNanos = now;
    }
}
