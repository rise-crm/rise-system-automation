package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookRegistrationServiceTest {

    private static final List<String> EVENTS = List.of("MESSAGES_UPSERT");

    @Test
    void registersMessageCheckCallbackAndReturnsInstanceToken() {
        EvolutionApiService evolution = mock(EvolutionApiService.class);
        when(evolution.fetchInstance("guardiao")).thenReturn(instance("guardiao", "TOKEN-1"));

        String token = new WebhookRegistrationService(evolution, "https://api.example.com").register("guardiao");

        assertEquals("TOKEN-1", token);
        verify(evolution).setWebhook("guardiao", "https://api.example.com/webhook/message-check", EVENTS);
    }

    @Test
    void trailingSlashInBaseUrlIsNotDuplicated() {
        EvolutionApiService evolution = mock(EvolutionApiService.class);
        when(evolution.fetchInstance("guardiao")).thenReturn(instance("guardiao", "TOKEN-1"));

        new WebhookRegistrationService(evolution, "https://api.example.com/").register("guardiao");

        verify(evolution).setWebhook("guardiao", "https://api.example.com/webhook/message-check", EVENTS);
    }

    @Test
    void unknownInstanceDoesNotSetWebhook() {
        EvolutionApiService evolution = mock(EvolutionApiService.class);
        when(evolution.fetchInstance(any())).thenReturn(null);

        String token = new WebhookRegistrationService(evolution, "https://api.example.com").register("missing");

        assertNull(token);
        verify(evolution, never()).setWebhook(anyString(), anyString(), anyList());
    }

    private static EvolutionInstance instance(String name, String token) {
        return new EvolutionInstance("968f508e-7108-4815-a287-ba8fe5d225e5", name, "open",
                "5511900000000@s.whatsapp.net", token);
    }
}
