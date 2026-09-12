package com.dariodussin.whatsappautomationbackend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerInstanceByGroup(
        @JsonProperty("campaign_id") String campaignId,
        @JsonProperty("instance_name") String instanceName,
        @JsonProperty("status") String status
) {
    public boolean isConnected() {
        if (status == null || status.isBlank()) {
            return false;
        }
        return "open".equalsIgnoreCase(status) || "connected".equalsIgnoreCase(status);
    }
}
