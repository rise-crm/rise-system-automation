package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobExecutionService {

    private final RiseApiService riseApiService;
    private final EvolutionApiService evolutionApiService;

    public JobExecutionService(RiseApiService riseApiService, EvolutionApiService evolutionApiService) {
        this.riseApiService = riseApiService;
        this.evolutionApiService = evolutionApiService;
    }

    public void execute(ScheduleJob task) throws Exception {
        String instanceName = riseApiService.getInstanceNameByCampaignId(task.campaignId());
        if (instanceName == null) {
            throw new Exception("Config Error: No WhatsApp instance for campaign " + task.campaignId());
        }
        handleTaskExecution(instanceName, task);
    }

    private void handleTaskExecution(String instance, ScheduleJob task) throws Exception {
        JobMetadata meta = task.metadata();

        List<String> groupIds = riseApiService.getGroupIdsByCampaignId(task.campaignId());

        if (groupIds == null || groupIds.isEmpty()) {
            System.err.println("No groups found for campaign: " + task.campaignId());
            return;
        }

        for (String groupId : groupIds) {
            try {
                executeSingleTask(instance, groupId, task, meta);

                long humanDelay = (long) (Math.random() * (5000 - 3000) + 3000);
                System.out.println("Sleeping for " + (humanDelay / 1000) + "s to avoid ban...");
                Thread.sleep(humanDelay);

                System.out.println("Successfully executed " + task.type() + " for group " + groupId);
            } catch (Exception e) {
                System.err.println("Failed for group " + groupId + ": " + e.getMessage());
                throw new Exception("Failed at group " + groupId + ": " + e.getMessage());
            }
        }
    }

    private void executeSingleTask(String instance, String groupId, ScheduleJob task, JobMetadata meta) {
        switch (task.type()) {
            case SEND_MESSAGE -> evolutionApiService.sendTextMessage(instance, groupId, meta);

            case SEND_IMAGE, SEND_VIDEO, SEND_DOCUMENT ->
                    evolutionApiService.sendMediaMessage(instance, groupId, task.type(), meta);

            case SEND_AUDIO -> evolutionApiService.sendAudioMessage(instance, groupId, meta);

            case RENAME_GROUP -> evolutionApiService.updateGroupSubject(instance, groupId, meta);

            case OPEN_GROUP -> evolutionApiService.updateGroupSettings(instance, groupId, "not_announcement");

            case CLOSE_GROUP -> evolutionApiService.updateGroupSettings(instance, groupId, "announcement");

            default -> throw new IllegalArgumentException("Unexpected value: " + task.type());
        }
    }
}
