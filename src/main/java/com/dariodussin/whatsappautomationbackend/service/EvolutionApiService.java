package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.model.JobType;
import com.dariodussin.whatsappautomationbackend.model.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;

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
                            "delay", 1200,
                            "linkPreview", true,
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
                "delay", 1200,
                "caption", meta.message() != null ? meta.message() : ""
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
            throw e; // This triggers your JobRunner's catch block to update Supabase
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
                "delay", 1200,
                "linkPreview", true,
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

}