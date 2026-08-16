package com.dariodussin.whatsappautomationbackend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

public record ScheduleJob(
        String id,
        @JsonProperty("campaign_id") String campaignId,
        @JsonProperty("scheduled_at") OffsetDateTime scheduledAt,
        JobType type,
        JobMetadata metadata, // Typed nested object
        String status,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("processed_at") OffsetDateTime processedAt
) {}