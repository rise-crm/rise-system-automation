package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.config.WebhookRateLimiter;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookRateLimiter webhookRateLimiter;
    private final String webhookToken;

    public WebhookController(WebhookService webhookService,
                             WebhookRateLimiter webhookRateLimiter,
                             @Value("${webhook.token}") String webhookToken) {
        this.webhookService = webhookService;
        this.webhookRateLimiter = webhookRateLimiter;
        this.webhookToken = webhookToken;
    }

    @PostMapping("/message-check")
    public ResponseEntity<Void> messageCheck(@RequestBody EvolutionWebhookPayload payload) {
        if (!apiKeyMatches(payload.apikey(), webhookToken)) {
            System.out.println("[WEBHOOK] Rejected message-check: invalid apikey");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!webhookRateLimiter.tryAcquire()) {
            System.out.println("[WEBHOOK] Rejected message-check: rate limit");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(webhookRateLimiter.retryAfterSeconds()))
                    .build();
        }
        webhookService.checkMessage(payload);
        return ResponseEntity.ok().build();
    }

    private static boolean apiKeyMatches(String provided, String expected) {
        if (provided == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
