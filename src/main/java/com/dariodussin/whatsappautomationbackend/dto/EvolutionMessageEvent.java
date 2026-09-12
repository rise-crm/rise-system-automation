package com.dariodussin.whatsappautomationbackend.dto;

public enum EvolutionMessageEvent {
    MESSAGES_UPSERT("messages.upsert"),
    MESSAGES_UPDATE("messages.update");

    private final String value;

    EvolutionMessageEvent(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static EvolutionMessageEvent from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = raw.trim().toLowerCase().replace('_', '.');
        for (EvolutionMessageEvent event : values()) {
            if (event.value.equals(normalized)) {
                return event;
            }
        }
        return null;
    }
}
