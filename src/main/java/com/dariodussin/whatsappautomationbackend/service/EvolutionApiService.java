package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.dto.GroupInfo;
import com.dariodussin.whatsappautomationbackend.dto.GroupParticipant;
import com.dariodussin.whatsappautomationbackend.dto.GroupParticipantsResponse;
import com.dariodussin.whatsappautomationbackend.dto.MessageKey;
import com.dariodussin.whatsappautomationbackend.model.JobType;
import com.dariodussin.whatsappautomationbackend.model.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.file.Paths;

@Service
public class EvolutionApiService {
    private final WebClient evolutionClient;

    public EvolutionApiService(@Qualifier("evolutionClient") WebClient evolutionApiService) {
        this.evolutionClient = evolutionApiService;
    }

    // 1. SEND TEXT
    public void sendTextMessage(String instance, String number, JobMetadata meta) {
        long startTime = System.currentTimeMillis();
        String messagePreview = meta.message() != null && meta.message().length() > 30
                ? meta.message().substring(0, 30) + "..."
                : meta.message();

        // 1. Log the Attempt
        System.out.printf("[INFO] [Evolution] Sending Text | Instance: %s | To: %s | Mentions: %b | Preview: \"%s\"%n",
                instance, number, meta.mentionAll(), messagePreview);

        try {
            evolutionClient.post()
                    .uri("/message/sendText/{instance}", instance)
                    .bodyValue(Map.of(
                            "number", number,
                            "text", meta.message(),
                            // "delay", 1200,
                            "linkPreview", false,
                            "mentionsEveryOne", Objects.requireNonNullElse(meta.mentionAll(), false)
                    ))
                    .retrieve()
                    // Capture 4xx/5xx errors specifically
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(String.class).flatMap(body -> {
                                return Mono.error(new RuntimeException("API Error: " + body));
                            })
                    )
                    .bodyToMono(Void.class)
                    .block();

            // 2. Log Success with Latency
            long duration = System.currentTimeMillis() - startTime;
            System.out.printf("[SUCCESS] [Evolution] Text sent to %s in %dms%n", number, duration);

        } catch (Exception e) {
            // 3. Log Critical Failure
            System.err.printf("[CRITICAL] [Evolution] Failed to dispatch message to %s! Reason: %s%n",
                    number, e.getMessage());

            // Rethrow so your calling service (like a Queue listener) knows the job failed
            throw e;
        }
    }

    // 2. SEND MEDIA (Images, Audio, Video, Documents)
    public void sendMediaMessage(String instance, String number, JobType jobType, JobMetadata meta) {
        MediaType type = MediaType.fromJobType(jobType);

        // The error says it wants "mediatype" (lowercase)
        // Let's ensure the payload matches exactly what the validator requested
        Map<String, Object> payload = Map.of(
                "number", number,
                "mediatype", type.getEvolutionValue(), // Must be "image", "video", etc.
                "media", meta.fileUrl(),
                //"delay", 1200,
                "caption", meta.message() != null ? meta.message() : "",
                "fileName", Paths.get(meta.fileUrl()).getFileName().toString()
        );

        System.out.println("[INFO] [EvolutionAPI] Sending Media | Type: " + type.getEvolutionValue());

        try {
            evolutionClient.post()
                    .uri("/message/sendMedia/{instance}", instance)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(String.class).flatMap(body -> {
                                // This will now catch and throw the error correctly
                                return Mono.error(new RuntimeException("Evolution API Error: " + body));
                            })
                    )
                    .bodyToMono(Void.class)
                    .block();

            System.out.println("[SUCCESS] Media sent to " + number);

        } catch (Exception e) {
            System.err.println("[CRITICAL] Media Send Failed: " + e.getMessage());
            throw e;
        }
    }

    // 3. RENAME GROUP
    public void updateGroupSubject(String instance, String groupJid, JobMetadata meta) {
        evolutionClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/group/updateGroupSubject/{instance}")
                        .queryParam("groupJid", groupJid) // Adds ?groupJid=... automatically
                        .build(instance))
                .bodyValue(Map.of("subject", meta.groupName()))
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    // 4. OPEN/CLOSE GROUP (updateSettings)
    public void updateGroupSettings(String instance, String groupJid, String action) {
        // action: "announcement" (Close) or "not_announcement" (Open)
        evolutionClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/group/updateSetting/{instance}")
                        .queryParam("groupJid", groupJid)
                        .build(instance))
                .bodyValue(Map.of("action", action))
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

    // 5. SEND AUDIO MESSAGE (separate endpoint)
    public void sendAudioMessage(String instance, String number, JobMetadata meta) {
        // Prepare payload for WhatsApp audio message
        Map<String, Object> payload = Map.of(
                "number", number,
                "audio", meta.fileUrl(),
                // "delay", 1200,
                "linkPreview", false,
                "mentionsEveryOne", Objects.requireNonNullElse(meta.mentionAll(), false)
        );

        System.out.println("[INFO] [EvolutionAPI] Sending Audio | Instance: " + instance);

        try {
            evolutionClient.post()
                    .uri("/message/sendWhatsAppAudio/{instance}", instance)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(String.class).flatMap(body -> {
                                return Mono.error(new RuntimeException("Evolution API Error: " + body));
                            })
                    )
                    .bodyToMono(Void.class)
                    .block();

            System.out.println("[SUCCESS] Audio sent to " + number);

        } catch (Exception e) {
            System.err.println("[CRITICAL] Audio Send Failed: " + e.getMessage());
            throw e;
        }
    }

    public GroupInfo findGroupInfo(String instance, String groupJid) {
        return evolutionClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/group/findGroupInfos/{instance}")
                        .queryParam("groupJid", groupJid)
                        .build(instance))
                .retrieve()
                .onStatus(status -> status.isError(), responseStatus ->
                        responseStatus.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException("Evolution API Error: " + body))))
                .bodyToMono(GroupInfo.class)
                .block();
    }

    public List<GroupParticipant> findGroupParticipants(String instance, String groupJid) {
        GroupParticipantsResponse response = evolutionClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/group/participants/{instance}")
                        .queryParam("groupJid", groupJid)
                        .build(instance))
                .retrieve()
                .onStatus(status -> status.isError(), responseStatus ->
                        responseStatus.bodyToMono(String.class).flatMap(body ->
                                Mono.error(new RuntimeException("Evolution API Error: " + body))))
                .bodyToMono(GroupParticipantsResponse.class)
                .block();

        if (response == null || response.participants() == null) {
            return List.of();
        }
        return response.participants();
    }

    public void deleteMessageForEveryone(String instance, MessageKey key, String participantJid) {
        if (key == null || key.id() == null || key.remoteJid() == null) {
            throw new IllegalArgumentException("Message key is incomplete for deletion");
        }
        if (participantJid == null || participantJid.isBlank()) {
            throw new IllegalArgumentException("Participant JID is required for deletion");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", key.id());
        payload.put("remoteJid", key.remoteJid());
        payload.put("fromMe", false);
        payload.put("participant", participantJid);

        System.out.println("[INFO] [Evolution] Deleting message");

        try {
            evolutionClient.method(HttpMethod.DELETE)
                    .uri("/chat/deleteMessageForEveryone/{instance}", instance)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(String.class).flatMap(body ->
                                    Mono.error(new RuntimeException("Evolution API Error: " + body))))
                    .bodyToMono(Void.class)
                    .block();

            System.out.println("[SUCCESS] [Evolution] Deleted message");
        } catch (Exception e) {
            System.err.printf("[CRITICAL] [Evolution] Failed to delete message: %s%n",
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    public void removeGroupParticipant(String instance, String groupJid, String participantJid) {
        System.out.println("[INFO] [Evolution] Removing participant");

        try {
            evolutionClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/group/updateParticipant/{instance}")
                            .queryParam("groupJid", groupJid)
                            .build(instance))
                    .bodyValue(Map.of(
                            "groupJid", groupJid,
                            "action", "remove",
                            "participants", List.of(participantJid)
                    ))
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(String.class).flatMap(body ->
                                    Mono.error(new RuntimeException("Evolution API Error: " + body))))
                    .bodyToMono(Void.class)
                    .block();

            System.out.println("[SUCCESS] [Evolution] Removed participant");
        } catch (Exception e) {
            System.err.printf("[CRITICAL] [Evolution] Failed to remove participant: %s%n",
                    e.getClass().getSimpleName());
            throw e;
        }
    }

}