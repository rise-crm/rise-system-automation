package com.dariodussin.whatsappautomationbackend.security;

import com.dariodussin.whatsappautomationbackend.config.WebhookRateLimiter;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionInstance;
import com.dariodussin.whatsappautomationbackend.service.EvolutionApiService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Component
public class InstanceTokenValidator {

    private final EvolutionApiService evolutionApiService;
    private final long ttlNanos;
    private final WebhookRateLimiter lookupLimiter;
    private final LongSupplier nanoTime;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    @Autowired
    public InstanceTokenValidator(EvolutionApiService evolutionApiService,
                                  @Value("${webhook.token-ttl}") long ttlSeconds,
                                  @Value("${webhook.token-lookup.permits-per-second}") double lookupsPerSecond,
                                  @Value("${webhook.token-lookup.burst}") double lookupBurst) {
        this(evolutionApiService, ttlSeconds,
                new WebhookRateLimiter(lookupsPerSecond, lookupBurst, System::nanoTime), System::nanoTime);
    }

    InstanceTokenValidator(EvolutionApiService evolutionApiService, long ttlSeconds,
                           WebhookRateLimiter lookupLimiter, LongSupplier nanoTime) {
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("webhook token TTL must not be negative");
        }
        this.evolutionApiService = evolutionApiService;
        this.ttlNanos = ttlSeconds * 1_000_000_000L;
        this.lookupLimiter = lookupLimiter;
        this.nanoTime = nanoTime;
    }

    public boolean isValid(String instanceName, String token) {
        if (instanceName == null || instanceName.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        String expected = instanceToken(instanceName);
        return expected != null && ApiKeyAuthenticationFilter.tokenMatches(token, expected);
    }

    private String instanceToken(String instanceName) {
        long now = nanoTime.getAsLong();
        CachedToken cached = cache.get(instanceName);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return cached.token();
        }

        if (!lookupLimiter.tryAcquire()) {
            System.err.printf("[AUTH] Instance token lookup throttled | instance=%s%n", instanceName);
            return null;
        }

        try {
            EvolutionInstance instance = evolutionApiService.fetchInstance(instanceName);
            if (instance == null || instance.token() == null || instance.token().isBlank()) {
                cache.remove(instanceName);
                return null;
            }
            if (ttlNanos > 0) {
                cache.put(instanceName, new CachedToken(instance.token(), now + ttlNanos));
            }
            return instance.token();
        } catch (RuntimeException e) {
            System.err.printf("[AUTH] Instance token check failed | instance=%s | error=%s%n",
                    instanceName, e.getClass().getSimpleName());
            return null;
        }
    }

    private record CachedToken(String token, long expiresAtNanos) {}
}
