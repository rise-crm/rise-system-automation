package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class JobExecutionService {

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final RiseApiService riseApiService;
    private final EvolutionApiService evolutionApiService;
    private final JobSendProgress sendProgress;
    private final Sleeper sleeper;

    @Autowired
    public JobExecutionService(RiseApiService riseApiService,
                               EvolutionApiService evolutionApiService,
                               JobSendProgress sendProgress) {
        this(riseApiService, evolutionApiService, sendProgress, Thread::sleep);
    }

    JobExecutionService(RiseApiService riseApiService,
                        EvolutionApiService evolutionApiService,
                        JobSendProgress sendProgress,
                        Sleeper sleeper) {
        this.riseApiService = riseApiService;
        this.evolutionApiService = evolutionApiService;
        this.sendProgress = sendProgress;
        this.sleeper = sleeper;
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
        String jobId = task.id();

        List<String> groupIds = riseApiService.getGroupIdsByCampaignId(task.campaignId());

        if (groupIds == null || groupIds.isEmpty()) {
            System.err.println("No groups found for campaign: " + task.campaignId());
            sendProgress.clear(jobId);
            return;
        }

        Set<String> alreadySent = sendProgress.alreadySent(jobId);
        List<String> remaining = new ArrayList<>();
        for (String groupId : groupIds) {
            if (alreadySent.contains(groupId)) {
                System.out.printf("[JOBS] Skip already-sent group | job=%s | group=%s%n", jobId, groupId);
            } else {
                remaining.add(groupId);
            }
        }

        if (remaining.isEmpty()) {
            System.out.printf("[JOBS] All groups already sent | job=%s%n", jobId);
            sendProgress.clear(jobId);
            return;
        }

        for (String groupId : remaining) {
            try {
                executeSingleTask(instance, groupId, task, meta);
                sendProgress.markSent(jobId, groupId);

                long humanDelay = (long) (Math.random() * (5000 - 3000) + 3000);
                System.out.println("Sleeping for " + (humanDelay / 1000) + "s to avoid ban...");
                sleeper.sleep(humanDelay);

                System.out.println("Successfully executed " + task.type() + " for group " + groupId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Interrupted at group " + groupId, e);
            } catch (Exception e) {
                System.err.println("Failed for group " + groupId + ": " + e.getMessage());
                throw new Exception("Failed at group " + groupId + ": " + e.getMessage(), e);
            }
        }

        sendProgress.clear(jobId);
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
