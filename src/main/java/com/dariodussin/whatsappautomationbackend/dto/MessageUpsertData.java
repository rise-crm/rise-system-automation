package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageUpsertData(
        MessageKey key,
        String pushName,
        String status,
        Map<String, Object> message,
        Map<String, Object> contextInfo,
        String messageType,
        Long messageTimestamp,
        String instanceId,
        String source
) {}
