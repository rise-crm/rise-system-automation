package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageUpdateData(
        String keyId,
        String remoteJid,
        Boolean fromMe,
        String participant,
        String status,
        Object pollUpdates,
        String instanceId,
        String messageId
) {}
