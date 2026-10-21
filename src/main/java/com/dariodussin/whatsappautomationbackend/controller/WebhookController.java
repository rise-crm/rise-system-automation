package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.config.WebhookRateLimiter;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final WebhookService webhookService;
    private final WebhookRateLimiter webhookRateLimiter;

    public WebhookController(WebhookService webhookService, WebhookRateLimiter webhookRateLimiter) {
        this.webhookService = webhookService;
        this.webhookRateLimiter = webhookRateLimiter;
    }

    @PostMapping("/message-check")
    public ResponseEntity<Void> messageCheck(@RequestBody EvolutionWebhookPayload payload) {
        long started = System.currentTimeMillis();
        String event = payload.event();
        System.out.printf("[WEBHOOK] message-check received | event=%s%n", event);

        if (!webhookRateLimiter.tryAcquire()) {
            System.out.printf("[WEBHOOK] message-check rejected | event=%s | reason=rate_limit | status=429 | duration=%dms%n",
                    event, System.currentTimeMillis() - started);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(webhookRateLimiter.retryAfterSeconds()))
                    .build();
        }

        try {
            webhookService.checkMessage(payload);
            System.out.printf("[WEBHOOK] message-check done | event=%s | status=200 | duration=%dms%n",
                    event, System.currentTimeMillis() - started);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            System.err.printf("[WEBHOOK] message-check failed | event=%s | status=500 | error=%s | duration=%dms%n",
                    event, e.getClass().getSimpleName(), System.currentTimeMillis() - started);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
