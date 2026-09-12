package com.dariodussin.whatsappautomationbackend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerGroupCampaignMatch(
        @JsonProperty("campaign_id") String campaignId,
        @JsonProperty("campaign_name") String campaignName,
        @JsonProperty("campaign_slug") String campaignSlug,
        @JsonProperty("is_guardian") Boolean isGuardian,
        @JsonProperty("group_id") String groupId,
        @JsonProperty("group_name") String groupName
) {}
