package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.dto.EvolutionWebhookPayload;
import com.dariodussin.whatsappautomationbackend.dto.GroupParticipant;
import com.dariodussin.whatsappautomationbackend.model.WorkerGroupCampaignResponse;
import com.dariodussin.whatsappautomationbackend.model.WorkerInstanceByGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    private static final String GROUP = "120363000000000000@g.us";
    private static final String GUARDIAN = "guardian-instance";
    private static final String ADMIN_INSTANCE = "admin-instance";
    private static final String MEMBER_PN = "5511999887766@s.whatsapp.net";
    private static final String MEMBER_LID = "111111111111111@lid";
    private static final String ADMIN_PN = "5511888777666@s.whatsapp.net";
    private static final String ADMIN_LID = "222222222222222@lid";

    @Mock
    private EvolutionApiService evolutionApiService;

    @Mock
    private RiseApiService riseApiService;

    private JsonMapper jsonMapper;
    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        webhookService = new WebhookService(jsonMapper, evolutionApiService, riseApiService);
        when(riseApiService.getGuardianGroupCampaign(GROUP)).thenReturn(
                new WorkerGroupCampaignResponse(GROUP, true, true, 1, List.of()));
        when(riseApiService.isGuardianInstanceForGroup(eq(GUARDIAN), any())).thenReturn(true);
        when(riseApiService.getAdminInstanceByGroupId(GROUP)).thenReturn(
                new WorkerInstanceByGroup("camp-1", ADMIN_INSTANCE, "open"));
    }

    @Test
    void forgedParticipantAltDoesNotRemoveAdmin() {
        when(evolutionApiService.findGroupParticipants(GUARDIAN, GROUP)).thenReturn(List.of(
                participant(MEMBER_PN, null),
                participant(ADMIN_PN, "admin")));

        webhookService.checkMessage(upsert(MEMBER_PN, ADMIN_PN));

        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), anyString());
        verify(evolutionApiService, never()).deleteMessageForEveryone(anyString(), any(), anyString());
    }

    @Test
    void unflaggedAdminLidLinkedToAdminPhoneIsNotRemoved() {
        when(evolutionApiService.findGroupParticipants(GUARDIAN, GROUP)).thenReturn(List.of(
                new GroupParticipant(ADMIN_LID, null, null, ADMIN_PN, false, false),
                participant(ADMIN_PN, "admin"),
                participant(MEMBER_PN, null)));

        webhookService.checkMessage(upsert(ADMIN_LID, ADMIN_PN));

        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), anyString());
    }

    @Test
    void conflictingLidAndPhoneDoNotRemoveAdmin() {
        when(evolutionApiService.findGroupParticipants(GUARDIAN, GROUP)).thenReturn(List.of(
                participant(ADMIN_LID, null),
                participant(ADMIN_PN, "admin")));

        webhookService.checkMessage(upsert(ADMIN_LID, ADMIN_PN));

        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), eq(ADMIN_PN));
        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), eq(ADMIN_LID));
    }

    @Test
    void adminMessageIsNotRemoved() {
        when(evolutionApiService.findGroupParticipants(GUARDIAN, GROUP)).thenReturn(List.of(
                new GroupParticipant(ADMIN_LID, "admin", ADMIN_PN, ADMIN_PN, true, false),
                participant(MEMBER_PN, null)));

        webhookService.checkMessage(upsert(ADMIN_LID, ADMIN_PN));

        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), anyString());
    }

    @Test
    void memberIsRemovedUsingRosterJidNotWebhookAlias() {
        when(evolutionApiService.findGroupParticipants(GUARDIAN, GROUP)).thenReturn(List.of(
                new GroupParticipant(MEMBER_LID, null, MEMBER_PN, MEMBER_PN, false, false),
                participant(ADMIN_PN, "admin")));

        webhookService.checkMessage(upsert(MEMBER_LID, "999999999999999@lid"));

        verify(evolutionApiService).removeGroupParticipant(ADMIN_INSTANCE, GROUP, MEMBER_PN);
        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), eq(ADMIN_PN));
        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), eq("999999999999999@lid"));
        verify(evolutionApiService).deleteMessageForEveryone(eq(ADMIN_INSTANCE), any(), eq(MEMBER_LID));
    }

    @Test
    void phoneUserPartDoesNotMatchLidOfAnotherPerson() {
        String sharedUser = "5511777666555";
        String adminPhone = sharedUser + "@s.whatsapp.net";
        String memberLid = sharedUser + "@lid";
        when(evolutionApiService.findGroupParticipants(GUARDIAN, GROUP)).thenReturn(List.of(
                participant(adminPhone, "admin"),
                participant(memberLid, null)));

        webhookService.checkMessage(upsert(memberLid, null));

        verify(evolutionApiService).removeGroupParticipant(ADMIN_INSTANCE, GROUP, memberLid);
        verify(evolutionApiService, never()).removeGroupParticipant(anyString(), anyString(), eq(adminPhone));
    }

    private static GroupParticipant participant(String id, String admin) {
        return new GroupParticipant(id, admin, null, null, null, null);
    }

    private EvolutionWebhookPayload upsert(String participant, String participantAlt) {
        Map<String, Object> key = new HashMap<>();
        key.put("remoteJid", GROUP);
        key.put("fromMe", false);
        key.put("id", "MSG1");
        key.put("participant", participant);
        if (participantAlt != null) {
            key.put("participantAlt", participantAlt);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("key", key);
        data.put("message", Map.of("conversation", "veja https://evil.example/promo"));
        data.put("messageType", "conversation");
        return new EvolutionWebhookPayload(
                "messages.upsert", GUARDIAN, jsonMapper.valueToTree(data),
                null, null, null, null, null);
    }
}
