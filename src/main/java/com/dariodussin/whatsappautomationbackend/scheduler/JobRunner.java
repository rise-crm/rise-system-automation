package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.service.EvolutionApiService;
import com.dariodussin.whatsappautomationbackend.service.RiseApiService;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@EnableScheduling
public class JobRunner {

    private final RiseApiService riseApiService;
    private final EvolutionApiService evolutionApiService;

    public JobRunner(RiseApiService riseApiService, EvolutionApiService evolutionApiService) {
        this.riseApiService = riseApiService;
        this.evolutionApiService = evolutionApiService;
    }

    @Scheduled(fixedDelay = 10000)
    public void runJobs() {
        List<ScheduleJob> tasks = riseApiService.fetchPendingTasks();
        if (tasks == null || tasks.isEmpty()) return;

        for (ScheduleJob task : tasks) {
            String jobId = task.id();
            try {
                riseApiService.updateJobStatus(jobId, JobStatus.EXECUTING, null);

                String instanceName = riseApiService.getInstanceNameByCampaignId(task.campaignId());
                if (instanceName == null) {
                    throw new Exception("Config Error: No WhatsApp instance for campaign " + task.campaignId());
                }

                handleTaskExecution(instanceName, task);

                riseApiService.updateJobStatus(jobId, JobStatus.COMPLETED, null);

            } catch (Exception e) {
                System.err.println("[CRITICAL] Catching failure for Job " + jobId + ": " + e.getMessage());
                riseApiService.updateJobStatus(jobId, JobStatus.ERROR, e.getMessage());
            }
        }
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
