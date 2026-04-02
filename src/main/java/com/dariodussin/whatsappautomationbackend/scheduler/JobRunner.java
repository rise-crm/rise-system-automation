package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.service.EvolutionApiService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.dariodussin.whatsappautomationbackend.service.SupabaseApiService;

import java.util.List;

@Component
@EnableScheduling
public class JobRunner {

    private final SupabaseApiService supabaseApiService;
    private final EvolutionApiService evolutionApiService;

    public JobRunner(SupabaseApiService supabaseApiService, EvolutionApiService evolutionApiService) {
        this.supabaseApiService = supabaseApiService;
        this.evolutionApiService = evolutionApiService;
    }

    @Scheduled(fixedDelay = 60000)
    public void runJobs() {
        List<ScheduleJob> tasks = supabaseApiService.fetchPendingTasks();
        if (tasks == null || tasks.isEmpty()) return;

        for (ScheduleJob task : tasks) {
            String jobId = task.id();
            try {
                supabaseApiService.updateJobStatus(jobId, JobStatus.EXECUTING, null);

                String instanceName = supabaseApiService.getInstanceNameByCampaignId(task.campaignId());
                if (instanceName == null) {
                    // This is a "handled" error
                    throw new Exception("Config Error: No WhatsApp instance for campaign " + task.campaignId());
                }

                // If handleTaskExecution throws, it jumps straight to the catch block below
                handleTaskExecution(instanceName, task);

                supabaseApiService.updateJobStatus(jobId, JobStatus.COMPLETED, null);

            } catch (Exception e) {
                // This now catches:
                // 1. Instance Name missing
                // 2. Evolution API errors (via Mono.error)
                // 3. Network timeouts
                System.err.println("[CRITICAL] Catching failure for Job " + jobId + ": " + e.getMessage());
                supabaseApiService.updateJobStatus(jobId, JobStatus.ERROR, e.getMessage());
            }
        }
    }

    private void handleTaskExecution(String instance, ScheduleJob task) throws Exception {
        JobMetadata meta = task.metadata();

        // 1. Get all groups targeted by this campaign
        List<String> groupIds = supabaseApiService.getGroupIdsByCampaignId(task.campaignId());

        if (groupIds == null || groupIds.isEmpty()) {
            System.err.println("No groups found for campaign: " + task.campaignId());
            return;
        }

        // 2. Loop through each group to perform the action
        for (String groupId : groupIds) {
            try {
                executeSingleTask(instance, groupId, task, meta);

                // Random delay between 5 to 15 seconds between each group
                long humanDelay = (long) (Math.random() * (15000 - 5000) + 5000);
                System.out.println("Sleeping for " + (humanDelay / 1000) + "s to avoid ban...");
                Thread.sleep(humanDelay);

                System.out.println("Successfully executed " + task.type() + " for group " + groupId);
            } catch (Exception e) {
                System.err.println("Failed for group " + groupId + ": " + e.getMessage());

                throw new Exception("Failed at group " + groupId + ": " + e.getMessage());
            }
        }
    }

    // Separate the specific API logic to keep code clean
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