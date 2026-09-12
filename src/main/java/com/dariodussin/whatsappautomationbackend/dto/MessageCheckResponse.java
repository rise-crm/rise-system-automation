package com.dariodussin.whatsappautomationbackend.dto;

public record MessageCheckResponse(
        String status,
        String event,
        boolean handled
) {}
