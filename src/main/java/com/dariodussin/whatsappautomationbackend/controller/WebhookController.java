package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.config.WebhookRateLimiter;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.dto.SetWebhookRequest;
import com.dariodussin.whatsappautomationbackend.dto.SetWebhookResponse;
import com.dariodussin.whatsappautomationbackend.service.WebhookRegistrationService;
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
    private final WebhookRegistrationService webhookRegistrationService;

    public WebhookController(WebhookService webhookService,
                             WebhookRateLimiter webhookRateLimiter,
                             WebhookRegistrationService webhookRegistrationService) {
        this.webhookService = webhookService;
        this.webhookRateLimiter = webhookRateLimiter;
        this.webhookRegistrationService = webhookRegistrationService;
    }

    @PostMapping
    public ResponseEntity<SetWebhookResponse> setWebhook(@RequestBody SetWebhookRequest request) {
        String instanceName = request.instanceName();
        if (instanceName == null || instanceName.isBlank()) {
            System.out.println("[WEBHOOK] set rejected | reason=missing_instance_name | status=400");
            return ResponseEntity.badRequest().build();
        }

        try {
            String token = webhookRegistrationService.register(instanceName);
            if (token == null) {
                System.out.printf("[WEBHOOK] set rejected | instance=%s | reason=instance_not_found | status=404%n",
                        instanceName);
                return ResponseEntity.notFound().build();
            }
            System.out.printf("[WEBHOOK] set done | instance=%s | status=200%n", instanceName);
            return ResponseEntity.ok(new SetWebhookResponse(instanceName, token));
        } catch (Exception e) {
            System.err.printf("[WEBHOOK] set failed | instance=%s | status=502 | error=%s%n",
                    instanceName, e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @PostMapping("/message-check")
    public ResponseEntity<Void> messageCheck(@RequestBody EvolutionWebhookPayload payload) {
        long started = System.currentTimeMillis();
        String event = payload.event();
        System.out.printf("[WEBHOOK] message-check received | event=%s | instance=%s%n", event, payload.instance());

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
            System.err.printf("[WEBHOOK] message-check failed | event=%s | instance=%s | status=500 | error=%s: %s | duration=%dms%n",
                    event, payload.instance(), e.getClass().getSimpleName(), e.getMessage(), System.currentTimeMillis() - started);
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
