package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.model.WorkerGroupCampaignResponse;
import com.dariodussin.whatsappautomationbackend.model.WorkerInstanceByGroup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiseApiService {

    private static final int PENDING_JOBS_LIMIT = 100;
    private static final int RETRY_JOBS_LIMIT = 3;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_LOOKBACK_HOURS = 12;

    private final WebClient edgeFunctionsClient;

    public RiseApiService(@Qualifier("edgeFunctionsClient") WebClient edgeFunctionsClient) {
        this.edgeFunctionsClient = edgeFunctionsClient;
    }

    public List<ScheduleJob> fetchPendingTasks() {
        return edgeFunctionsClient.get()
                .uri(uri -> uri.pathSegment("worker-jobs")
                        .queryParam("status", "pending")
                        .queryParam("limit", PENDING_JOBS_LIMIT)
                        .build())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException("Rise API GET /worker-jobs failed: " + body))))
                .bodyToMono(new ParameterizedTypeReference<List<ScheduleJob>>() {})
                .block();
    }

    public List<ScheduleJob> fetchRetryableTasks() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime dateFrom = now.minusHours(RETRY_LOOKBACK_HOURS);

        return edgeFunctionsClient.get()
                .uri(uri -> uri.pathSegment("worker-jobs")
                        .queryParam("status", "error")
                        .queryParam("date_from", dateFrom.toString())
                        .queryParam("date_to", now.toString())
                        .queryParam("max_retries", MAX_RETRIES)
                        .queryParam("limit", RETRY_JOBS_LIMIT)
                        .build())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException(
                                        "Rise API GET /worker-jobs (retry) failed: " + body))))
                .bodyToMono(new ParameterizedTypeReference<List<ScheduleJob>>() {})
                .block();
    }

    public void updateJobStatus(String jobId, JobStatus status, String errorMessage) {
        updateJobStatus(jobId, status, errorMessage, false);
    }

    public void updateJobStatus(String jobId, JobStatus status, String errorMessage, boolean incrementRetry) {
        try {
            System.out.printf("[DB-UPDATE] Job: %s | New Status: %s | increment_retry=%s%n",
                    jobId, status, incrementRetry);

            Map<String, Object> body = new HashMap<>();
            body.put("id", jobId);
            body.put("status", status.name().toLowerCase());

            if (errorMessage != null) {
                body.put("error_message", errorMessage);
            }
            if (incrementRetry) {
                body.put("increment_retry", true);
            }

            edgeFunctionsClient.patch()
                    .uri(uri -> uri.pathSegment("worker-jobs").build())
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(statusCode -> statusCode.isError(), response ->
                            response.bodyToMono(String.class).flatMap(error -> {
                                System.err.println("[RISE-ERROR] PATCH /worker-jobs failed: " + error);
                                return Mono.error(new RuntimeException("Rise API PATCH /worker-jobs failed"));
                            }))
                    .bodyToMono(Void.class)
                    .block();
        } catch (Exception e) {
            System.err.println("[CRITICAL] Failed to update job status: " + e.getMessage());
        }
    }

    public String getInstanceNameByCampaignId(String campaignId) {
        try {
            List<Map<String, Object>> response = edgeFunctionsClient.get()
                    .uri(uri -> uri.pathSegment("worker-instance")
                            .queryParam("campaign_id", campaignId)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.isError() && status.value() != 404, resp ->
                            resp.bodyToMono(String.class).flatMap(body ->
                                    Mono.error(new RuntimeException("Rise API GET /worker-instance failed: " + body))))
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block();

            if (response == null || response.isEmpty()) {
                return null;
            }

            Object instanceName = response.get(0).get("instance_name");
            return instanceName != null ? instanceName.toString() : null;
        } catch (WebClientResponseException.NotFound e) {
            return null;
        }
    }

    public List<String> getGroupIdsByCampaignId(String campaignId) {
        try {
            List<Map<String, Object>> response = edgeFunctionsClient.get()
                    .uri(uri -> uri.pathSegment("worker-groups")
                            .queryParam("campaign_id", campaignId)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.isError() && status.value() != 404, resp ->
                            resp.bodyToMono(String.class).flatMap(body ->
                                    Mono.error(new RuntimeException("Rise API GET /worker-groups failed: " + body))))
                    .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                    .block();

            if (response == null) {
                return List.of();
            }

            return response.stream()
                    .map(row -> row.get("group_id"))
                    .filter(groupId -> groupId != null)
                    .map(Object::toString)
                    .toList();
        } catch (WebClientResponseException.NotFound e) {
            return List.of();
        }
    }

    public WorkerGroupCampaignResponse getGuardianGroupCampaign(String groupId) {
        try {
            WorkerGroupCampaignResponse response = edgeFunctionsClient.get()
                    .uri(uri -> uri.pathSegment("worker-group-campaign")
                            .queryParam("group_id", groupId)
                            .queryParam("guardian", true)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.isError() && status.value() != 404, resp ->
                            resp.bodyToMono(String.class).flatMap(body ->
                                    Mono.error(new RuntimeException(
                                            "Rise API GET /worker-group-campaign failed: " + body))))
                    .bodyToMono(WorkerGroupCampaignResponse.class)
                    .block();

            return response;
        } catch (WebClientResponseException.NotFound e) {
            return null;
        } catch (Exception e) {
            System.err.printf("[RISE-ERROR] GET /worker-group-campaign failed: %s%n",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    public boolean isGuardianInstanceForGroup(String instanceName, WorkerGroupCampaignResponse groupCampaign) {
        if (instanceName == null || instanceName.isBlank() || groupCampaign == null) {
            return false;
        }

        for (var match : groupCampaign.guardianMatches()) {
            String campaignInstance = getInstanceNameByCampaignId(match.campaignId());
            if (campaignInstance != null && campaignInstance.equalsIgnoreCase(instanceName)) {
                return true;
            }
        }
        return false;
    }

    public WorkerInstanceByGroup getAdminInstanceByGroupId(String groupId) {
        try {
            WorkerInstanceByGroup instance = edgeFunctionsClient.get()
                    .uri(uri -> uri.pathSegment("worker-instance-by-group")
                            .queryParam("group_id", groupId)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.isError() && status.value() != 404, resp ->
                            resp.bodyToMono(String.class).flatMap(body ->
                                    Mono.error(new RuntimeException(
                                            "Rise API GET /worker-instance-by-group failed: " + body))))
                    .bodyToMono(WorkerInstanceByGroup.class)
                    .block();

            if (instance == null || instance.instanceName() == null || instance.instanceName().isBlank()) {
                return null;
            }
            return instance;
        } catch (WebClientResponseException.NotFound e) {
            return null;
        } catch (Exception e) {
            System.err.printf("[RISE-ERROR] GET /worker-instance-by-group failed: %s%n",
                    e.getClass().getSimpleName());
            return null;
        }
    }
}
