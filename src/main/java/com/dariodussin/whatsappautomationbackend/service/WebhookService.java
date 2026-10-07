package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionMessageEvent;
import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.dto.GroupInfo;
import com.dariodussin.whatsappautomationbackend.dto.GroupParticipant;
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

    public void checkMessage(EvolutionWebhookPayload payload) {
        EvolutionMessageEvent event = EvolutionMessageEvent.from(payload.event());
        if (event == null) {
            System.out.printf("[WEBHOOK] Ignoring unhandled event: %s%n", payload.event());
            return;
        }

        switch (event) {
            case MESSAGES_UPSERT -> handleUpsert(payload);
            case MESSAGES_UPDATE -> handleUpdate(payload);
        }
    }

    private void handleUpsert(EvolutionWebhookPayload payload) {
        MessageUpsertData data = jsonMapper.convertValue(payload.data(), MessageUpsertData.class);
        MessageKey key = data != null ? data.key() : null;
        String messageText = data != null
                ? extractVisibleText(data.message(), data.contextInfo())
                : "";

        System.out.printf(
                "[WEBHOOK] messages.upsert | FromMe: %s | Type: %s | Status: %s%n",
                key != null ? key.fromMe() : null,
                data != null ? data.messageType() : null,
                data != null ? data.status() : null);

        if (data == null || key == null || Boolean.TRUE.equals(key.fromMe())) {
            return;
        }

        if (!isGroupJid(key.remoteJid())) {
            return;
        }

        String groupJid = key.remoteJid();
        boolean isTemplate = isTemplateMessage(data);
        boolean hasLink = containsLink(messageText);
        boolean isScamTrigger = hasLink || isTemplate;

        WorkerGroupCampaignResponse groupCampaign = riseApiService.getGuardianGroupCampaign(groupJid);
        if (groupCampaign == null || !groupCampaign.isGuardianLinked()) {
            System.out.printf("[WEBHOOK] Skipping | group is not linked to a guardian campaign (linked=%s, guardian_linked=%s)%n",
                    groupCampaign != null ? groupCampaign.linked() : null,
                    groupCampaign != null ? groupCampaign.guardianLinked() : null);
            return;
        }
        if (!riseApiService.isGuardianInstanceForGroup(payload.instance(), groupCampaign)) {
            System.out.println("[WEBHOOK] Skipping | instance is not the guardian for the group");
            return;
        }

        boolean groupClosed = false;
        if (!isScamTrigger) {
            groupClosed = isGroupClosed(payload.instance(), groupJid);
            if (!groupClosed) {
                return;
            }
        }

        String triggerReason = groupClosed
                ? "closed_group"
                : (isTemplate ? "template" : "link");

        WorkerInstanceByGroup adminInstance = riseApiService.getAdminInstanceByGroupId(groupJid);
        if (adminInstance == null) {
            System.out.println("[WEBHOOK] Skipping | group is not linked to any campaign admin instance");
            return;
        }
        if (!adminInstance.isConnected()) {
            System.out.printf("[WEBHOOK] Skipping | admin instance is not connected (status: %s)%n",
                    adminInstance.status());
            return;
        }
        if (adminInstance.instanceName().equalsIgnoreCase(payload.instance())) {
            System.out.println("[WEBHOOK] Skipping | no separate admin instance for the group");
            return;
        }

        String adminInstanceName = adminInstance.instanceName();
        String campaignId = adminInstance.campaignId();

        String contactJid = resolveContactJid(key);
        if (contactJid == null) {
            System.out.printf("[WEBHOOK] %s detected but sender JID is missing%n", triggerReason);
            return;
        }

        try {
            List<GroupParticipant> participants = evolutionApiService.findGroupParticipants(
                    payload.instance(), groupJid);
            SenderMatch match = matchSender(participants, key);
            if (match.status() == SenderMatchStatus.ADMIN) {
                System.out.println("[WEBHOOK] Skipping | sender is admin");
                return;
            }
            if (match.status() == SenderMatchStatus.CONFLICT) {
                System.out.println("[WEBHOOK] Skipping | sender JIDs do not identify one participant");
                return;
            }
            if (match.status() != SenderMatchStatus.OK) {
                System.out.println("[WEBHOOK] Skipping | sender not found in group");
                return;
            }

            String participantJid = canonicalClusterJid(match.cluster());
            if (!isUsableContactJid(participantJid) || belongsToAdmin(participants, participantJid)) {
                System.out.println("[WEBHOOK] Skipping | refusing to remove participant");
                return;
            }

            System.out.printf("[WEBHOOK] Moderating | campaign: %s | reason: %s%n",
                    campaignId, triggerReason);

            String deleteJid = firstOwnedJid(match.cluster(), key.participant(), key.participantAlt(), key.senderPn());
            if (!isUsableContactJid(deleteJid) || belongsToAdmin(participants, deleteJid)) {
                deleteJid = participantJid;
            }
            evolutionApiService.deleteMessageForEveryone(adminInstanceName, key, deleteJid);
            if (isScamTrigger || groupClosed) {
                evolutionApiService.removeGroupParticipant(adminInstanceName, groupJid, participantJid);
            }
        } catch (Exception e) {
            System.err.printf("[WEBHOOK] Failed guard action (%s) for campaign %s: %s%n",
                    triggerReason, campaignId, e.getClass().getSimpleName());
        }
    }

    private boolean isGroupClosed(String instance, String groupJid) {
        try {
            GroupInfo groupInfo = evolutionApiService.findGroupInfo(instance, groupJid);
            boolean closed = groupInfo != null && groupInfo.isClosed();
            System.out.printf("[WEBHOOK] Group announce/closed=%s%n", closed);
            return closed;
        } catch (Exception e) {
            System.err.printf("[WEBHOOK] Failed to read group settings: %s%n",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private void handleUpdate(EvolutionWebhookPayload payload) {
        MessageUpdateData data = jsonMapper.convertValue(payload.data(), MessageUpdateData.class);

        System.out.printf("[WEBHOOK] messages.update | FromMe: %s | Status: %s%n",
                data != null ? data.fromMe() : null,
                data != null ? data.status() : null);
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

    private enum SenderMatchStatus {
        OK, ADMIN, NOT_FOUND, CONFLICT
    }

    private record SenderMatch(SenderMatchStatus status, List<GroupParticipant> cluster) {
        private static SenderMatch of(SenderMatchStatus status) {
            return new SenderMatch(status, List.of());
        }
    }

    private static SenderMatch matchSender(List<GroupParticipant> participants, MessageKey key) {
        List<String> aliases = contactAliases(key);
        if (participants == null || participants.isEmpty() || aliases.isEmpty()) {
            return SenderMatch.of(SenderMatchStatus.NOT_FOUND);
        }

        List<GroupParticipant> matched = new ArrayList<>();
        for (GroupParticipant participant : participants) {
            if (ownsAnyAlias(participant, aliases)) {
                matched.add(participant);
            }
        }
        if (matched.isEmpty()) {
            return SenderMatch.of(SenderMatchStatus.NOT_FOUND);
        }

        List<GroupParticipant> cluster = identityCluster(participants, matched);
        if (!isSingleIdentity(cluster)) {
            return SenderMatch.of(SenderMatchStatus.CONFLICT);
        }
        if (cluster.stream().anyMatch(GroupParticipant::isGroupAdmin)) {
            return new SenderMatch(SenderMatchStatus.ADMIN, cluster);
        }

        String preferred = resolveContactJid(key);
        if (isUsableContactJid(preferred) && rosterContains(participants, preferred) && !clusterOwns(cluster, preferred)) {
            return SenderMatch.of(SenderMatchStatus.CONFLICT);
        }
        return new SenderMatch(SenderMatchStatus.OK, cluster);
    }

    private static List<GroupParticipant> identityCluster(List<GroupParticipant> participants,
                                                          List<GroupParticipant> seeds) {
        List<GroupParticipant> cluster = new ArrayList<>(seeds);
        boolean expanded = true;
        while (expanded) {
            expanded = false;
            List<GroupParticipant> additions = new ArrayList<>();
            for (GroupParticipant candidate : participants) {
                if (cluster.contains(candidate) || additions.contains(candidate)) {
                    continue;
                }
                for (int i = 0; i < cluster.size(); i++) {
                    if (sameRosterIdentity(cluster.get(i), candidate)) {
                        additions.add(candidate);
                        break;
                    }
                }
            }
            if (!additions.isEmpty()) {
                cluster.addAll(additions);
                expanded = true;
            }
        }
        return cluster;
    }

    private static boolean isSingleIdentity(List<GroupParticipant> cluster) {
        if (cluster.isEmpty()) {
            return false;
        }
        GroupParticipant anchor = cluster.get(0);
        for (int i = 1; i < cluster.size(); i++) {
            if (!sameRosterIdentity(anchor, cluster.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRosterIdentity(GroupParticipant left, GroupParticipant right) {
        for (String leftId : identifiers(left)) {
            for (String rightId : identifiers(right)) {
                if (sameContact(leftId, rightId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> identifiers(GroupParticipant participant) {
        List<String> ids = new ArrayList<>(3);
        addAlias(ids, participant.id());
        addAlias(ids, participant.jid());
        addAlias(ids, participant.phoneNumber());
        return ids;
    }

    private static boolean ownsAnyAlias(GroupParticipant participant, List<String> aliases) {
        return matchesAny(aliases, participant.id(), participant.jid(), participant.phoneNumber());
    }

    private static boolean rosterContains(List<GroupParticipant> participants, String jid) {
        for (GroupParticipant participant : participants) {
            if (matchesAny(List.of(jid), participant.id(), participant.jid(), participant.phoneNumber())) {
                return true;
            }
        }
        return false;
    }

    private static boolean clusterOwns(List<GroupParticipant> cluster, String jid) {
        for (GroupParticipant participant : cluster) {
            if (matchesAny(List.of(jid), participant.id(), participant.jid(), participant.phoneNumber())) {
                return true;
            }
        }
        return false;
    }

    private static boolean belongsToAdmin(List<GroupParticipant> participants, String jid) {
        for (GroupParticipant participant : participants) {
            if (participant.isGroupAdmin()
                    && matchesAny(List.of(jid), participant.id(), participant.jid(), participant.phoneNumber())) {
                return true;
            }
        }
        return false;
    }

    private static String canonicalClusterJid(List<GroupParticipant> cluster) {
        for (GroupParticipant participant : cluster) {
            if (isUsableContactJid(participant.jid())) {
                return participant.jid();
            }
        }
        for (GroupParticipant participant : cluster) {
            if (isUsableContactJid(participant.id())) {
                return participant.id();
            }
        }
        for (GroupParticipant participant : cluster) {
            if (isUsableContactJid(participant.phoneNumber())) {
                return participant.phoneNumber();
            }
        }
        return null;
    }

    private static String firstOwnedJid(List<GroupParticipant> cluster, String... candidates) {
        for (String candidate : candidates) {
            if (isUsableContactJid(candidate) && clusterOwns(cluster, candidate)) {
                return candidate;
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
        if (isUsableContactJid(value)) {
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
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        if (left.equalsIgnoreCase(right)) {
            return true;
        }
        String leftNamespace = namespace(left);
        String rightNamespace = namespace(right);
        if (leftNamespace.isEmpty() || !leftNamespace.equals(rightNamespace)) {
            return false;
        }
        return userPart(left).equalsIgnoreCase(userPart(right));
    }

    private static String namespace(String jid) {
        int at = jid.indexOf('@');
        if (at < 0 || at == jid.length() - 1) {
            return "";
        }
        String domain = jid.substring(at + 1).toLowerCase();
        return switch (domain) {
            case "s.whatsapp.net", "c.us" -> "pn";
            default -> domain;
        };
    }

    private static String userPart(String jid) {
        int at = jid.indexOf('@');
        String user = at >= 0 ? jid.substring(0, at) : jid;
        int colon = user.indexOf(':');
        return colon >= 0 ? user.substring(0, colon) : user;
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
}
