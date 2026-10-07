package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.config.WebhookRateLimiter;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WebhookControllerRateLimitTest {

    @Test
    void invalidKeyDoesNotConsumeQuotaOrCallEvolution() {
        WebhookService webhookService = mock(WebhookService.class);
        WebhookRateLimiter limiter = new WebhookRateLimiter(10, 1, () -> 0L);
        WebhookController controller = new WebhookController(webhookService, limiter, "secret");

        var rejected = controller.messageCheck(payload("wrong"));

        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatusCode());
        assertNull(rejected.getBody());
        verify(webhookService, never()).checkMessage(any());
        assertEquals(true, limiter.tryAcquire());
    }

    @Test
    void authenticatedExcessReturns429WithoutCallingService() {
        WebhookService webhookService = mock(WebhookService.class);
        WebhookRateLimiter limiter = new WebhookRateLimiter(10, 1, () -> 0L);
        WebhookController controller = new WebhookController(webhookService, limiter, "secret");

        var allowed = controller.messageCheck(payload("secret"));
        var limited = controller.messageCheck(payload("secret"));

        assertEquals(HttpStatus.OK, allowed.getStatusCode());
        assertNull(allowed.getBody());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode());
        assertNull(limited.getBody());
        assertEquals("1", limited.getHeaders().getFirst("Retry-After"));
        verify(webhookService).checkMessage(any());
    }

    private static EvolutionWebhookPayload payload(String apikey) {
        return new EvolutionWebhookPayload(null, null, null, null, null, null, null, apikey);
    }
}
