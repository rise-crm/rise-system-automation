package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.dto.MessageCheckResponse;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.springframework.beans.factory.annotation.Value;
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
    private final String evolutionKey;

    public WebhookController(WebhookService webhookService,
                             @Value("${evolution.key}") String evolutionKey) {
        this.webhookService = webhookService;
        this.evolutionKey = evolutionKey;
    }

    @PostMapping("/message-check")
    public ResponseEntity<MessageCheckResponse> messageCheck(@RequestBody EvolutionWebhookPayload payload) {
        if (!apiKeyMatches(payload.apikey(), evolutionKey)) {
            System.out.println("[WEBHOOK] Rejected message-check: invalid apikey");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(webhookService.checkMessage(payload));
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
