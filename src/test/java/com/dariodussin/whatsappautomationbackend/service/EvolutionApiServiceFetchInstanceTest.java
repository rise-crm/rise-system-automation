package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionInstance;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvolutionApiServiceFetchInstanceTest {

    private static final String RESPONSE = """
            [{"id":"968f508e-7108-4815-a287-ba8fe5d225e5","name":"campaign-canal-guardiao-d68010d7",
            "connectionStatus":"open","ownerJid":"5511900000000@s.whatsapp.net","profileName":"Test",
            "integration":"WHATSAPP-BAILEYS","number":null,"businessId":null,
            "token":"11111111-2222-3333-4444-555555555555","clientName":"evolution_exchange",
            "Chatwoot":null,"Proxy":null,
            "Setting":{"id":"setting-1","rejectCall":false},
            "_count":{"Message":307,"Contact":690,"Chat":82}}]
            """;

    @Test
    void parsesInstanceAndQueriesByName() {
        AtomicReference<ClientRequest> sent = new AtomicReference<>();
        EvolutionApiService service = serviceReturning(HttpStatus.OK, RESPONSE, sent);

        EvolutionInstance instance = service.fetchInstance("campaign-canal-guardiao-d68010d7");

        assertEquals("11111111-2222-3333-4444-555555555555", instance.token());
        assertEquals("968f508e-7108-4815-a287-ba8fe5d225e5", instance.id());
        assertEquals("open", instance.connectionStatus());
        assertEquals("/instance/fetchInstances", sent.get().url().getPath());
        assertEquals("instanceName=campaign-canal-guardiao-d68010d7", sent.get().url().getQuery());
    }

    @Test
    void returnsNullWhenNameDoesNotMatch() {
        EvolutionApiService service = serviceReturning(HttpStatus.OK, RESPONSE, new AtomicReference<>());

        assertNull(service.fetchInstance("other-instance"));
    }

    @Test
    void returnsNullOnNotFound() {
        EvolutionApiService service = serviceReturning(HttpStatus.NOT_FOUND,
                "{\"status\":404,\"error\":\"Not Found\"}", new AtomicReference<>());

        assertNull(service.fetchInstance("missing"));
    }

    @Test
    void throwsOnServerError() {
        EvolutionApiService service = serviceReturning(HttpStatus.INTERNAL_SERVER_ERROR, "boom",
                new AtomicReference<>());

        assertThrows(RuntimeException.class, () -> service.fetchInstance("guardiao"));
    }

    private static EvolutionApiService serviceReturning(HttpStatus status, String body,
                                                        AtomicReference<ClientRequest> sent) {
        WebClient client = WebClient.builder()
                .baseUrl("http://evolution.test")
                .exchangeFunction(request -> {
                    sent.set(request);
                    return Mono.just(ClientResponse.create(status)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build());
                })
                .build();
        return new EvolutionApiService(client);
    }
}
