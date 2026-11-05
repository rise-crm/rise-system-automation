package com.dariodussin.whatsappautomationbackend.config;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

final class HttpConnectors {

    private HttpConnectors() {
    }

    static ClientHttpConnector withTimeouts(int connectTimeoutMs, long responseTimeoutMs) {
        if (connectTimeoutMs <= 0 || responseTimeoutMs <= 0) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(responseTimeoutMs));
        return new ReactorClientHttpConnector(httpClient);
    }
}
