package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebhookRegistrationService {

    static final String CALLBACK_PATH = "/webhook/message-check";
    static final List<String> EVENTS = List.of("MESSAGES_UPSERT");

    private final EvolutionApiService evolutionApiService;
    private final String callbackUrl;

    public WebhookRegistrationService(EvolutionApiService evolutionApiService,
                                      @Value("${base.url}") String baseUrl) {
        this.evolutionApiService = evolutionApiService;
        this.callbackUrl = stripTrailingSlash(baseUrl) + CALLBACK_PATH;
    }

    /** Retorna o token da instância, ou null se ela não existir (nesse caso o webhook não é configurado). */
    public String register(String instanceName) {
        EvolutionInstance instance = evolutionApiService.fetchInstance(instanceName);
        if (instance == null) {
            return null;
        }
        evolutionApiService.setWebhook(instanceName, callbackUrl, EVENTS);
        return instance.token();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
