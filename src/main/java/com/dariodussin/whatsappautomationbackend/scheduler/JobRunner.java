package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.service.JobExecutionService;
import com.dariodussin.whatsappautomationbackend.service.JobPollingState;
import com.dariodussin.whatsappautomationbackend.service.RiseApiService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JobRunner {

    private final RiseApiService riseApiService;
    private final JobExecutionService jobExecutionService;
    private final JobPollingState pollingState;

    public JobRunner(RiseApiService riseApiService,
                     JobExecutionService jobExecutionService,
                     JobPollingState pollingState) {
        this.riseApiService = riseApiService;
        this.jobExecutionService = jobExecutionService;
        this.pollingState = pollingState;
    }

    @Scheduled(fixedDelay = 10000)
    public void runJobs() {
        if (!pollingState.isEnabled()) {
            return;
        }
        List<ScheduleJob> tasks;
        try {
            tasks = riseApiService.fetchPendingTasks();
        } catch (Exception e) {
            System.err.printf("[JOBS] Poll failed | error=%s%n", e.getClass().getSimpleName());
            return;
        }
        if (tasks == null || tasks.isEmpty()) {
            return;
        }

        System.out.printf("[JOBS] Processing %d pending job(s)%n", tasks.size());
        for (ScheduleJob task : tasks) {
            String jobId = task.id();
            try {
                System.out.printf("[JOBS] Start | id=%s | type=%s%n", jobId, task.type());
                if (!riseApiService.updateJobStatus(jobId, JobStatus.EXECUTING, null)) {
                    System.err.printf("[JOBS] Claim failed | id=%s | skipping to avoid duplicate send%n", jobId);
                    continue;
                }

                jobExecutionService.execute(task);

                if (!riseApiService.updateJobStatus(jobId, JobStatus.COMPLETED, null)) {
                    System.err.printf("[JOBS] Completed locally but status PATCH failed | id=%s%n", jobId);
                } else {
                    System.out.printf("[JOBS] Completed | id=%s%n", jobId);
                }

            } catch (Exception e) {
                System.err.println("[CRITICAL] Catching failure for Job " + jobId + ": " + e.getMessage());
                if (!riseApiService.updateJobStatus(jobId, JobStatus.ERROR, e.getMessage(), true)) {
                    System.err.printf("[JOBS] Failed to mark error status | id=%s%n", jobId);
                }
            }
        }
    }
}
