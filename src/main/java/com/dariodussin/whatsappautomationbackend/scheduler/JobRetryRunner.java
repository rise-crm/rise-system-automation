package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.service.JobExecutionService;
import com.dariodussin.whatsappautomationbackend.service.RiseApiService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JobRetryRunner {

    private static final long MIN_DELAY_BETWEEN_JOBS_MS = 60_000;
    private static final long MAX_DELAY_BETWEEN_JOBS_MS = 75_000;

    private final RiseApiService riseApiService;
    private final JobExecutionService jobExecutionService;

    public JobRetryRunner(RiseApiService riseApiService, JobExecutionService jobExecutionService) {
        this.riseApiService = riseApiService;
        this.jobExecutionService = jobExecutionService;
    }

    @Scheduled(fixedDelay = 180_000)
    public void retryJobs() {
        List<ScheduleJob> tasks = riseApiService.fetchRetryableTasks();
        if (tasks == null || tasks.isEmpty()) return;

        System.out.printf("[RETRY] Found %d job(s) to retry%n", tasks.size());

        for (int i = 0; i < tasks.size(); i++) {
            ScheduleJob task = tasks.get(i);
            String jobId = task.id();
            try {
                riseApiService.updateJobStatus(jobId, JobStatus.EXECUTING, null);

                jobExecutionService.execute(task);

                riseApiService.updateJobStatus(jobId, JobStatus.COMPLETED, null);
                System.out.printf("[RETRY] Job %s completed successfully%n", jobId);

            } catch (Exception e) {
                System.err.printf("[RETRY] Failure for Job %s: %s%n", jobId, e.getMessage());
                riseApiService.updateJobStatus(jobId, JobStatus.ERROR, e.getMessage(), true);
            }

            if (i < tasks.size() - 1) {
                sleepBetweenJobs();
            }
        }
    }

    private void sleepBetweenJobs() {
        long delay = MIN_DELAY_BETWEEN_JOBS_MS
                + (long) (Math.random() * (MAX_DELAY_BETWEEN_JOBS_MS - MIN_DELAY_BETWEEN_JOBS_MS));
        System.out.printf("[RETRY] Sleeping %ds between jobs to avoid ban...%n", delay / 1000);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[RETRY] Sleep interrupted");
        }
    }
}
