package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageKey(
        String remoteJid,
        Boolean fromMe,
        String id,
        String participant,
        String participantAlt,
        String senderPn,
        String remoteJidAlt
) {}
