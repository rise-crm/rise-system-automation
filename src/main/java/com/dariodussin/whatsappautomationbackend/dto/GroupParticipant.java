package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GroupParticipant(
        String id,
        String admin,
        String jid,
        String phoneNumber,
        Boolean isAdmin,
        Boolean isSuperAdmin
) {
    public boolean isGroupAdmin() {
        if (Boolean.TRUE.equals(isAdmin) || Boolean.TRUE.equals(isSuperAdmin)) {
            return true;
        }
        if (admin == null || admin.isBlank()) {
            return false;
        }
        return "admin".equalsIgnoreCase(admin) || "superadmin".equalsIgnoreCase(admin);
    }
}
