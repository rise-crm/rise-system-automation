package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RiseApiService {

    private static final int PENDING_JOBS_LIMIT = 100;

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

    public void updateJobStatus(String jobId, JobStatus status, String errorMessage) {
        try {
            System.out.printf("[DB-UPDATE] Job: %s | New Status: %s%n", jobId, status);

            Map<String, Object> body = new HashMap<>();
            body.put("id", jobId);
            body.put("status", status.name().toLowerCase());

            if (errorMessage != null) {
                body.put("error_message", errorMessage);
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
}
