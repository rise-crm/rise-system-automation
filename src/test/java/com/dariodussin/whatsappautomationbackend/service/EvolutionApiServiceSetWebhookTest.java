package com.dariodussin.whatsappautomationbackend.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvolutionApiServiceSetWebhookTest {

    private static final List<String> EVENTS = List.of(
            "SEND_MESSAGE", "MESSAGES_UPSERT", "MESSAGES_UPDATE", "MESSAGES_DELETE");

    @Test
    void sendsSettingsWrappedInWebhookObject() {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        EvolutionApiService service = serviceReturning(HttpStatus.CREATED, sent);

        service.setWebhook("guardiao", "https://api.example.com/webhook/message-check", EVENTS);

        assertEquals("/webhook/set/guardiao", sent.get().url().getPath());
        JsonNode webhook = bodyOf(sent.get()).get("webhook");
        assertTrue(webhook.get("enabled").asBoolean());
        assertEquals("https://api.example.com/webhook/message-check", webhook.get("url").asString());
        assertEquals(4, webhook.get("events").size());
        assertEquals("SEND_MESSAGE", webhook.get("events").get(0).asString());
        assertTrue(webhook.get("headers").isObject());
        assertFalse(webhook.get("byEvents").asBoolean());
        assertTrue(webhook.get("base64").asBoolean());
    }

    @Test
    void throwsOnEvolutionError() {
        EvolutionApiService service = serviceReturning(HttpStatus.BAD_REQUEST, new AtomicReference<>());

        assertThrows(RuntimeException.class,
                () -> service.setWebhook("guardiao", "https://api.example.com/webhook/message-check", EVENTS));
    }

    private static EvolutionApiService serviceReturning(HttpStatus status, AtomicReference<ClientRequest> sent) {
        WebClient client = WebClient.builder()
                .baseUrl("http://evolution.test")
                .exchangeFunction(request -> {
                    sent.set(request);
                    return Mono.just(ClientResponse.create(status).body("{}").build());
                })
                .build();
        return new EvolutionApiService(client);
    }

    private static JsonNode bodyOf(ClientRequest request) {
        MockClientHttpRequest captured = new MockClientHttpRequest(HttpMethod.POST, URI.create("/"));
        request.body().insert(captured, new BodyInserter.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return ExchangeStrategies.withDefaults().messageWriters();
            }

            @Override
            public Optional<ServerHttpRequest> serverRequest() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> hints() {
                return Map.of();
            }
        }).block();
        return JsonMapper.builder().build().readTree(captured.getBodyAsString().block());
    }
}
