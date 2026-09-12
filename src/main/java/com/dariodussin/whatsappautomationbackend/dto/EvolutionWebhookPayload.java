package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvolutionWebhookPayload(
        String event,
        String instance,
        JsonNode data,
        String destination,
        @JsonProperty("date_time") String dateTime,
        String sender,
        @JsonProperty("server_url") String serverUrl,
        String apikey
) {}
