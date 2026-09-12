package com.dariodussin.whatsappautomationbackend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkerGroupCampaignResponse(
        @JsonProperty("group_id") String groupId,
        @JsonProperty("linked") Boolean linked,
        @JsonProperty("guardian_linked") Boolean guardianLinked,
        @JsonProperty("count") Integer count,
        @JsonProperty("matches") List<WorkerGroupCampaignMatch> matches
) {
    public boolean isGuardianLinked() {
        return Boolean.TRUE.equals(linked) && Boolean.TRUE.equals(guardianLinked);
    }

    public List<WorkerGroupCampaignMatch> guardianMatches() {
        if (matches == null) {
            return List.of();
        }
        return matches.stream()
                .filter(match -> Boolean.TRUE.equals(match.isGuardian()))
                .toList();
    }
}
