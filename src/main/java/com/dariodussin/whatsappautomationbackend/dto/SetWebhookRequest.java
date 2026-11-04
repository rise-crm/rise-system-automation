package com.dariodussin.whatsappautomationbackend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SetWebhookRequest(String instanceName) {}
