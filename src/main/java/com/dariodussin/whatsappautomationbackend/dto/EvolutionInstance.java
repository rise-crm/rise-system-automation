package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvolutionInstance(
        String id,
        String name,
        String connectionStatus,
        String ownerJid,
        String token
) {}
