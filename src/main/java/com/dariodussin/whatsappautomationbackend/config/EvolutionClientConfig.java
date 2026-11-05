package com.dariodussin.whatsappautomationbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class EvolutionClientConfig {
    @Value("${evolution.url}")
    private String evolutionUrl;

    @Value("${evolution.key}")
    private String evolutionKey;

    @Value("${http.connect-timeout-ms}")
    private int connectTimeoutMs;

    @Value("${evolution.response-timeout-ms}")
    private long responseTimeoutMs;

    @Bean
    public WebClient evolutionClient() {
        return WebClient.builder()
                .baseUrl(evolutionUrl)
                .clientConnector(HttpConnectors.withTimeouts(connectTimeoutMs, responseTimeoutMs))
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("apikey", evolutionKey)
                .build();
    }
}
