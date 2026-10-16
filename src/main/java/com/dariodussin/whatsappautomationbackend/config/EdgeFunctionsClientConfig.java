package com.dariodussin.whatsappautomationbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class EdgeFunctionsClientConfig {

    @Value("${edge-functions.url}")
    private String edgeFunctionsUrl;

    @Value("${edge-functions.key}")
    private String edgeFunctionsKey;

    @Bean
    public WebClient edgeFunctionsClient() {
        String baseUrl = edgeFunctionsUrl.endsWith("/")
                ? edgeFunctionsUrl.substring(0, edgeFunctionsUrl.length() - 1)
                : edgeFunctionsUrl;

        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("x-api-token", edgeFunctionsKey)
                .build();
    }
}
