package com.dariodussin.whatsappautomationbackend.config;

import com.dariodussin.whatsappautomationbackend.controller.JobPollingController;
import com.dariodussin.whatsappautomationbackend.controller.WebhookController;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionInstance;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.security.ApiKeyAuthenticationFilter;
import com.dariodussin.whatsappautomationbackend.security.InstanceTokenValidator;
import com.dariodussin.whatsappautomationbackend.service.EvolutionApiService;
import com.dariodussin.whatsappautomationbackend.service.JobPollingState;
import com.dariodussin.whatsappautomationbackend.service.WebhookRegistrationService;
import com.dariodussin.whatsappautomationbackend.service.WebhookService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {JobPollingController.class, WebhookController.class})
@Import({SecurityConfig.class, InstanceTokenValidator.class, SecurityConfigTest.RateLimiterConfig.class})
@TestPropertySource(properties = {
        "admin.token=admin-secret",
        "evolution.key=evolution-key",
        "webhook.token-ttl=0",
        "webhook.token-lookup.permits-per-second=1000",
        "webhook.token-lookup.burst=1000"
})
class SecurityConfigTest {

    private static final String ADMIN = ApiKeyAuthenticationFilter.ADMIN_HEADER;
    private static final String WEBHOOK = ApiKeyAuthenticationFilter.WEBHOOK_HEADER;
    private static final String EVOLUTION_KEY = "evolution-key";
    private static final String INSTANCE = "guardiao";
    private static final String INSTANCE_TOKEN = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JobPollingState pollingState;

    @MockitoBean
    private WebhookService webhookService;

    @MockitoBean
    private WebhookRegistrationService webhookRegistrationService;

    @MockitoBean
    private EvolutionApiService evolutionApiService;

    @TestConfiguration
    static class RateLimiterConfig {
        @Bean
        WebhookRateLimiter webhookRateLimiter() {
            return new WebhookRateLimiter(1000, 1000, System::nanoTime);
        }
    }

    @BeforeEach
    void stubInstance() {
        when(evolutionApiService.fetchInstance(INSTANCE)).thenReturn(
                new EvolutionInstance("968f508e", INSTANCE, "open", null, INSTANCE_TOKEN));
    }

    private static String messageCheck(String instance, String apikey) {
        StringBuilder json = new StringBuilder("{\"event\":\"messages.upsert\"");
        if (instance != null) {
            json.append(",\"instance\":\"").append(instance).append('"');
        }
        if (apikey != null) {
            json.append(",\"apikey\":\"").append(apikey).append('"');
        }
        return json.append('}').toString();
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
    void evolutionKeyDoesNotOpenAdmin() throws Exception {
        mvc.perform(get("/admin/polling").header(ADMIN, EVOLUTION_KEY))
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
    void webhookWithEvolutionKeyPassesWithoutFetchingInstance() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck(INSTANCE, EVOLUTION_KEY)))
                .andExpect(status().isOk());
        verify(evolutionApiService, never()).fetchInstance(any());
        verify(webhookService).checkMessage(any());
    }

    @Test
    void webhookWithInstanceTokenReachesServiceWithIntactPayload() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck(INSTANCE, INSTANCE_TOKEN)))
                .andExpect(status().isOk());

        ArgumentCaptor<EvolutionWebhookPayload> payload = ArgumentCaptor.forClass(EvolutionWebhookPayload.class);
        verify(webhookService).checkMessage(payload.capture());
        assertEquals("messages.upsert", payload.getValue().event());
        assertEquals(INSTANCE, payload.getValue().instance());
        assertEquals(INSTANCE_TOKEN, payload.getValue().apikey());
    }

    @Test
    void webhookWithWrongApiKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck(INSTANCE, "wrong")))
                .andExpect(status().isUnauthorized());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void webhookWithoutApiKeyIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck(INSTANCE, null)))
                .andExpect(status().isUnauthorized());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void webhookWithInstanceTokenButNoInstanceIsUnauthorizedWithoutCallingEvolution() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck(null, INSTANCE_TOKEN)))
                .andExpect(status().isUnauthorized());
        verify(evolutionApiService, never()).fetchInstance(any());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void webhookWithTokenOfAnotherInstanceIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck("other", INSTANCE_TOKEN)))
                .andExpect(status().isUnauthorized());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void webhookWithInstanceTokenIsUnauthorizedWhenEvolutionIsDown() throws Exception {
        when(evolutionApiService.fetchInstance(INSTANCE)).thenThrow(new RuntimeException("down"));

        mvc.perform(post("/webhook/message-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck(INSTANCE, INSTANCE_TOKEN)))
                .andExpect(status().isUnauthorized());
        verify(webhookService, never()).checkMessage(any());
    }

    @Test
    void adminTokenDoesNotOpenWebhook() throws Exception {
        mvc.perform(post("/webhook/message-check")
                        .header(ADMIN, "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(messageCheck(INSTANCE, "admin-secret")))
                .andExpect(status().isUnauthorized());
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
    void setWebhookWithEvolutionKeyReturnsInstanceToken() throws Exception {
        when(webhookRegistrationService.register(INSTANCE)).thenReturn(INSTANCE_TOKEN);

        mvc.perform(post("/webhook")
                        .header(WEBHOOK, EVOLUTION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"guardiao\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceName").value(INSTANCE))
                .andExpect(jsonPath("$.token").value(INSTANCE_TOKEN));
    }

    @Test
    void setWebhookWithInstanceTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook")
                        .header(WEBHOOK, INSTANCE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"guardiao\"}"))
                .andExpect(status().isUnauthorized());
        verify(webhookRegistrationService, never()).register(any());
    }

    @Test
    void setWebhookForUnknownInstanceIsNotFound() throws Exception {
        when(webhookRegistrationService.register("missing")).thenReturn(null);

        mvc.perform(post("/webhook")
                        .header(WEBHOOK, EVOLUTION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"missing\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void setWebhookWithoutHeaderIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"guardiao\"}"))
                .andExpect(status().isUnauthorized());
        verify(webhookRegistrationService, never()).register(any());
    }

    @Test
    void setWebhookWithWrongHeaderIsUnauthorized() throws Exception {
        mvc.perform(post("/webhook")
                        .header(WEBHOOK, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"guardiao\"}"))
                .andExpect(status().isUnauthorized());
        verify(webhookRegistrationService, never()).register(any());
    }

    @Test
    void setWebhookIgnoresApiKeyInBody() throws Exception {
        mvc.perform(post("/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"guardiao\",\"apikey\":\"" + EVOLUTION_KEY + "\"}"))
                .andExpect(status().isUnauthorized());
        verify(webhookRegistrationService, never()).register(any());
    }

    @Test
    void adminTokenDoesNotOpenSetWebhook() throws Exception {
        mvc.perform(post("/webhook")
                        .header(WEBHOOK, "admin-secret")
                        .header(ADMIN, "admin-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"guardiao\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setWebhookWithBlankInstanceNameIsBadRequest() throws Exception {
        mvc.perform(post("/webhook")
                        .header(WEBHOOK, EVOLUTION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\" \"}"))
                .andExpect(status().isBadRequest());
        verify(webhookRegistrationService, never()).register(any());
    }

    @Test
    void setWebhookWhenEvolutionFailsIsBadGateway() throws Exception {
        doThrow(new RuntimeException("down")).when(webhookRegistrationService).register(INSTANCE);

        mvc.perform(post("/webhook")
                        .header(WEBHOOK, EVOLUTION_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceName\":\"guardiao\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void unknownRouteIsUnauthorized() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isUnauthorized());
    }
}
