package com.dariodussin.whatsappautomationbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebhookRateLimitConfig {

    @Bean
    public WebhookRateLimiter webhookRateLimiter(
            @Value("${webhook.rate-limit.permits-per-second}") double permitsPerSecond,
            @Value("${webhook.rate-limit.burst}") double burst) {
        return new WebhookRateLimiter(permitsPerSecond, burst, System::nanoTime);
    }
}
