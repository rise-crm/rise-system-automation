package com.dariodussin.whatsappautomationbackend.config;

import com.dariodussin.whatsappautomationbackend.controller.JobPollingController;
import com.dariodussin.whatsappautomationbackend.controller.WebhookController;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.security.ApiKeyAuthenticationFilter;
import com.dariodussin.whatsappautomationbackend.service.JobPollingState;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {JobPollingController.class, WebhookController.class})
@Import({SecurityConfig.class, SecurityConfigTest.RateLimiterConfig.class})
@TestPropertySource(properties = {"admin.token=admin-secret", "webhook.token=webhook-secret"})
class SecurityConfigTest {

    private static final String ADMIN = ApiKeyAuthenticationFilter.ADMIN_HEADER;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JobPollingState pollingState;

    @MockitoBean
    private WebhookService webhookService;

    @TestConfiguration
    static class RateLimiterConfig {
        @Bean
        WebhookRateLimiter webhookRateLimiter() {
            return new WebhookRateLimiter(1000, 1000, System::nanoTime);
        }
    }

    @Test
    void adminWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/admin/polling"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
        verify(pollingState, never()).isEnabled();
    }

    @Test
    void adminWithWrongTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/admin/polling/toggle").header(ADMIN, "wrong"))
                .andExpect(status().isUnauthorized());
        verify(pollingState, never()).toggle();
    }

    @Test
    void webhookTokenDoesNotOpenAdmin() throws Exception {
        mvc.perform(get("/admin/polling").header(ADMIN, "webhook-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminGetReturnsState() throws Exception {
        when(pollingState.isEnabled()).thenReturn(true);

        mvc.perform(get("/admin/polling").header(ADMIN, "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void adminToggleReturnsNewState() throws Exception {
        when(pollingState.toggle()).thenReturn(false);

        mvc.perform(post("/admin/polling/toggle").header(ADMIN, "admin-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void adminToggleWithRedisDownIsServiceUnavailable() throws Exception {
        when(pollingState.toggle()).thenThrow(new RedisConnectionFailureException("down"));

        mvc.perform(post("/admin/polling/toggle").header(ADMIN, "admin-secret"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void webhookWithWrongApiKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"messages.upsert\",\"apikey\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void webhookWithoutApiKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"messages.upsert\"}"))
                .andExpect(status().isUnauthorized());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void adminTokenHeaderDoesNotOpenWebhook() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .header(ADMIN, "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"messages.upsert\",\"apikey\":\"admin-secret\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookWithValidApiKeyReachesServiceWithIntactPayload() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"messages.upsert\",\"instance\":\"guardiao\",\"apikey\":\"webhook-secret\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<EvolutionWebhookPayload> payload = ArgumentCaptor.forClass(EvolutionWebhookPayload.class);
        verify(webhookService).checkMessage(payload.capture());
        assertEquals("messages.upsert", payload.getValue().event());
        assertEquals("guardiao", payload.getValue().instance());
        assertEquals("webhook-secret", payload.getValue().apikey());
    }

    @Test
    void oversizedWebhookBodyIsRejected() throws Exception {
        byte[] body = new byte[1024 * 1024 + 1];
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isContentTooLarge());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void unknownRouteIsUnauthorized() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isUnauthorized());
    }
}
