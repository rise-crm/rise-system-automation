package com.dariodussin.whatsappautomationbackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HttpConnectorsTest {

    @Test
    void silentServerFailsWithinResponseTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(10_000);
                } catch (Exception ignored) {
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            WebClient client = WebClient.builder()
                    .baseUrl("http://localhost:" + server.getLocalPort())
                    .clientConnector(HttpConnectors.withTimeouts(1_000, 300))
                    .build();

            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThrows(Exception.class, () ->
                            client.get().uri("/").retrieve().bodyToMono(String.class).block()));
            acceptor.interrupt();
        }
    }

    @Test
    void nonPositiveTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HttpConnectors.withTimeouts(0, 1_000));
        assertThrows(IllegalArgumentException.class, () -> HttpConnectors.withTimeouts(1_000, 0));
    }
}
