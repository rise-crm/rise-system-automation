package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.dto.MessageCheckResponse;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/message-check")
    public ResponseEntity<MessageCheckResponse> messageCheck(@RequestBody EvolutionWebhookPayload payload) {
        return ResponseEntity.ok(webhookService.checkMessage(payload));
    }
}
