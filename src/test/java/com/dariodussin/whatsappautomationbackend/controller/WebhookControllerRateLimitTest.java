package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.config.WebhookRateLimiter;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.service.WebhookRegistrationService;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebhookControllerRateLimitTest {

    @Test
    void excessReturns429WithoutCallingService() {
        WebhookService webhookService = mock(WebhookService.class);
        WebhookRateLimiter limiter = new WebhookRateLimiter(10, 1, () -> 0L);
        WebhookController controller = new WebhookController(webhookService, limiter,
                mock(WebhookRegistrationService.class));

        var allowed = controller.messageCheck(payload());
        var limited = controller.messageCheck(payload());

        assertEquals(HttpStatus.OK, allowed.getStatusCode());
        assertNull(allowed.getBody());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode());
        assertNull(limited.getBody());
        assertEquals("1", limited.getHeaders().getFirst("Retry-After"));
        verify(webhookService).checkMessage(any());
    }

    private static EvolutionWebhookPayload payload() {
        return new EvolutionWebhookPayload(null, null, null, null, null, null, null, "secret");
    }
}
