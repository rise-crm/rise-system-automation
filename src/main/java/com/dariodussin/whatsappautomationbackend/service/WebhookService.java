package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionMessageEvent;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.dto.GroupParticipant;
import com.dariodussin.whatsappautomationbackend.dto.MessageCheckResponse;
import com.dariodussin.whatsappautomationbackend.dto.MessageKey;
import com.dariodussin.whatsappautomationbackend.dto.MessageUpdateData;
import com.dariodussin.whatsappautomationbackend.dto.MessageUpsertData;
import com.dariodussin.whatsappautomationbackend.model.WorkerGroupCampaignResponse;
import com.dariodussin.whatsappautomationbackend.model.WorkerInstanceByGroup;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class WebhookService {

    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?i)((https?://|www\\.)\\S+|\\b(wa\\.me|t\\.me|bit\\.ly)/\\S+|\\b[a-z0-9][a-z0-9-]*\\.(com|com\\.br|net|org|io|app|me|co|xyz|link)(/\\S*)?)"
    );

    private static final Set<String> TEXT_KEYS = Set.of(
            "conversation", "text", "caption", "matchedText", "canonicalUrl", "description"
    );

    private final JsonMapper jsonMapper;
    private final EvolutionApiService evolutionApiService;
    private final RiseApiService riseApiService;

    public WebhookService(JsonMapper jsonMapper,
                          EvolutionApiService evolutionApiService,
                          RiseApiService riseApiService) {
        this.jsonMapper = jsonMapper;
        this.evolutionApiService = evolutionApiService;
        this.riseApiService = riseApiService;
    }

    public MessageCheckResponse checkMessage(EvolutionWebhookPayload payload) {
        EvolutionMessageEvent event = EvolutionMessageEvent.from(payload.event());
        if (event == null) {
            System.out.printf("[WEBHOOK] Ignoring unhandled event: %s | Instance: %s%n",
                    payload.event(), payload.instance());
            return new MessageCheckResponse("ok", payload.event(), false);
        }

        return switch (event) {
            case MESSAGES_UPSERT -> handleUpsert(payload);
            case MESSAGES_UPDATE -> handleUpdate(payload);
        };
    }

    private MessageCheckResponse handleUpsert(EvolutionWebhookPayload payload) {
        MessageUpsertData data = jsonMapper.convertValue(payload.data(), MessageUpsertData.class);
        MessageKey key = data != null ? data.key() : null;
        String messageText = data != null
                ? extractVisibleText(data.message(), data.contextInfo())
                : "";
        String senderJid = key != null ? resolveContactJid(key) : null;

        System.out.printf(
                "[WEBHOOK] messages.upsert | Instance: %s | FromMe: %s | RemoteJid: %s | MessageId: %s | Sender: %s | PushName: %s | Type: %s | Status: %s | Message: \"%s\"%n",
                payload.instance(),
                key != null ? key.fromMe() : null,
                key != null ? key.remoteJid() : null,
                key != null ? key.id() : null,
                senderJid,
                data != null ? data.pushName() : null,
                data != null ? data.messageType() : null,
                data != null ? data.status() : null,
                preview(messageText));

        if (data == null || key == null || Boolean.TRUE.equals(key.fromMe())) {
            return new MessageCheckResponse("ok", payload.event(), true);
        }

        if (!isGroupJid(key.remoteJid())) {
            return new MessageCheckResponse("ok", payload.event(), true);
        }

        boolean isTemplate = isTemplateMessage(data);
        boolean hasLink = containsLink(messageText);
        if (!hasLink && !isTemplate) {
            return new MessageCheckResponse("ok", payload.event(), true);
        }

        String groupJid = key.remoteJid();
        String triggerReason = isTemplate ? "template" : "link";
        WorkerGroupCampaignResponse groupCampaign = riseApiService.getGuardianGroupCampaign(groupJid);
        if (groupCampaign == null || !groupCampaign.isGuardianLinked()) {
            System.out.printf("[WEBHOOK] Skipping | Group %s is not linked to a guardian campaign (linked=%s, guardian_linked=%s)%n",
                    groupJid,
                    groupCampaign != null ? groupCampaign.linked() : null,
                    groupCampaign != null ? groupCampaign.guardianLinked() : null);
            return new MessageCheckResponse("ok", payload.event(), true);
        }
        if (!riseApiService.isGuardianInstanceForGroup(payload.instance(), groupCampaign)) {
            System.out.printf("[WEBHOOK] Skipping | Instance %s is not the guardian for group %s%n",
                    payload.instance(), groupJid);
            return new MessageCheckResponse("ok", payload.event(), true);
        }

        WorkerInstanceByGroup adminInstance = riseApiService.getAdminInstanceByGroupId(groupJid);
        if (adminInstance == null) {
            System.out.printf("[WEBHOOK] Skipping | Group %s is not linked to any campaign admin instance%n",
                    groupJid);
            return new MessageCheckResponse("ok", payload.event(), true);
        }
        if (!adminInstance.isConnected()) {
            System.out.printf("[WEBHOOK] Skipping | Admin instance %s is not connected (status: %s)%n",
                    adminInstance.instanceName(), adminInstance.status());
            return new MessageCheckResponse("ok", payload.event(), true);
        }
        if (adminInstance.instanceName().equalsIgnoreCase(payload.instance())) {
            System.out.printf("[WEBHOOK] Skipping | No separate admin instance for group %s (only guardian found)%n",
                    groupJid);
            return new MessageCheckResponse("ok", payload.event(), true);
        }

        String adminInstanceName = adminInstance.instanceName();
        String campaignId = adminInstance.campaignId();

        String contactJid = resolveContactJid(key);
        if (contactJid == null) {
            System.out.printf("[WEBHOOK] %s detected in group %s but sender JID is missing%n",
                    triggerReason, groupJid);
            return new MessageCheckResponse("ok", payload.event(), true);
        }

        try {
            List<GroupParticipant> participants = evolutionApiService.findGroupParticipants(
                    payload.instance(), groupJid);
            GroupParticipant sender = findParticipant(participants, key);
            if (sender != null && sender.isGroupAdmin()) {
                System.out.printf("[WEBHOOK] Skipping ban | %s is admin in %s%n", contactJid, groupJid);
                return new MessageCheckResponse("ok", payload.event(), true);
            }
            if (sender == null) {
                System.out.printf("[WEBHOOK] Skipping ban | %s not found in group %s%n", contactJid, groupJid);
                return new MessageCheckResponse("ok", payload.event(), true);
            }

            String participantJid = canonicalParticipantJid(sender);
            if (participantJid == null) {
                System.out.printf("[WEBHOOK] Skipping ban | Could not resolve canonical JID for %s in %s%n",
                        contactJid, groupJid);
                return new MessageCheckResponse("ok", payload.event(), true);
            }

            System.out.printf("[WEBHOOK] Moderating %s in %s via admin %s | campaign: %s | reason: %s | content: \"%s\"%n",
                    participantJid, groupJid, adminInstanceName, campaignId, triggerReason, preview(messageText));

            evolutionApiService.deleteMessageForEveryone(adminInstanceName, key);
            evolutionApiService.removeGroupParticipant(adminInstanceName, groupJid, participantJid);
        } catch (Exception e) {
            System.err.printf("[WEBHOOK] Failed guard action (%s) for %s in %s (campaign %s): %s%n",
                    triggerReason, contactJid, groupJid, campaignId, e.getMessage());
        }

        return new MessageCheckResponse("ok", payload.event(), true);
    }

    private MessageCheckResponse handleUpdate(EvolutionWebhookPayload payload) {
        MessageUpdateData data = jsonMapper.convertValue(payload.data(), MessageUpdateData.class);

        System.out.printf("[WEBHOOK] messages.update | Instance: %s | FromMe: %s | RemoteJid: %s | KeyId: %s | Status: %s%n",
                payload.instance(),
                data != null ? data.fromMe() : null,
                data != null ? data.remoteJid() : null,
                data != null ? data.keyId() : null,
                data != null ? data.status() : null);

        return new MessageCheckResponse("ok", payload.event(), true);
    }

    private static boolean isGroupJid(String jid) {
        return jid != null && jid.endsWith("@g.us");
    }

    private static String resolveContactJid(MessageKey key) {
        if (isUsableContactJid(key.participantAlt())) {
            return key.participantAlt();
        }
        if (isUsableContactJid(key.senderPn())) {
            return key.senderPn();
        }
        if (isUsableContactJid(key.participant())) {
            return key.participant();
        }
        return null;
    }

    private static boolean isUsableContactJid(String jid) {
        return jid != null && !jid.isBlank() && !jid.endsWith("@g.us");
    }

    private static String canonicalParticipantJid(GroupParticipant participant) {
        if (participant.jid() != null && !participant.jid().isBlank()) {
            return participant.jid();
        }
        if (participant.id() != null && !participant.id().isBlank()) {
            return participant.id();
        }
        if (participant.phoneNumber() != null && !participant.phoneNumber().isBlank()) {
            return participant.phoneNumber();
        }
        return null;
    }

    private static GroupParticipant findParticipant(List<GroupParticipant> participants, MessageKey key) {
        List<String> aliases = contactAliases(key);
        for (GroupParticipant participant : participants) {
            if (matchesAny(aliases, participant.id(), participant.jid(), participant.phoneNumber())) {
                return participant;
            }
        }
        return null;
    }

    private static List<String> contactAliases(MessageKey key) {
        List<String> aliases = new ArrayList<>();
        addAlias(aliases, key.participant());
        addAlias(aliases, key.participantAlt());
        addAlias(aliases, key.senderPn());
        return aliases;
    }

    private static void addAlias(List<String> aliases, String value) {
        if (value != null && !value.isBlank()) {
            aliases.add(value);
        }
    }

    private static boolean matchesAny(List<String> aliases, String... candidateIds) {
        for (String candidate : candidateIds) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (String alias : aliases) {
                if (sameContact(alias, candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameContact(String left, String right) {
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        return userPart(left).equalsIgnoreCase(userPart(right));
    }

    private static String userPart(String jid) {
        int at = jid.indexOf('@');
        return at >= 0 ? jid.substring(0, at) : jid;
    }

    private static String extractVisibleText(Map<String, Object> message, Map<String, Object> contextInfo) {
        StringBuilder text = new StringBuilder();
        collectText(message, text);
        collectText(contextInfo, text);
        return text.toString();
    }

    private static void collectText(Object node, StringBuilder out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String field = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (TEXT_KEYS.contains(field) && value instanceof String string && !string.isBlank()) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(string);
                } else {
                    collectText(value, out);
                }
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                collectText(item, out);
            }
        }
    }

    private static final Set<String> TEMPLATE_MESSAGE_KEYS = Set.of(
            "templateMessage",
            "templateButtonReplyMessage",
            "hydratedTemplate",
            "highlyStructuredMessage",
            "templateMessageReply"
    );

    private static boolean isTemplateMessage(MessageUpsertData data) {
        if (data == null) {
            return false;
        }

        String messageType = data.messageType();
        if (messageType != null && messageType.toLowerCase().contains("template")) {
            return true;
        }

        return containsTemplatePayload(data.message());
    }

    private static boolean containsTemplatePayload(Map<String, Object> message) {
        if (message == null || message.isEmpty()) {
            return false;
        }

        for (String key : message.keySet()) {
            if (TEMPLATE_MESSAGE_KEYS.contains(key)) {
                return true;
            }
            if (key.toLowerCase().contains("template")) {
                return true;
            }
        }

        for (Object value : message.values()) {
            if (containsTemplatePayload(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTemplatePayload(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (TEMPLATE_MESSAGE_KEYS.contains(key) || key.toLowerCase().contains("template")) {
                    return true;
                }
                if (containsTemplatePayload(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof List<?> list) {
            for (Object item : list) {
                if (containsTemplatePayload(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsLink(String text) {
        return text != null && !text.isBlank() && LINK_PATTERN.matcher(text).find();
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flattened = text.replaceAll("\\s+", " ").trim();
        return flattened.length() > 80 ? flattened.substring(0, 80) + "..." : flattened;
    }
}
