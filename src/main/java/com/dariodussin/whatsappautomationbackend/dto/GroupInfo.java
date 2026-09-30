package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupInfo(
        String id,
        String subject,
        Boolean announce,
        Boolean restrict
) {
    /** Grupo fechado: apenas admins podem enviar mensagens. */
    public boolean isClosed() {
        return Boolean.TRUE.equals(announce);
    }
}
