package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.service.JobExecutionService;
import com.dariodussin.whatsappautomationbackend.service.RiseApiService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JobRunner {

    private final RiseApiService riseApiService;
    private final JobExecutionService jobExecutionService;

    public JobRunner(RiseApiService riseApiService, JobExecutionService jobExecutionService) {
        this.riseApiService = riseApiService;
        this.jobExecutionService = jobExecutionService;
    }

    @Scheduled(fixedDelay = 10000)
    public void runJobs() {
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
                riseApiService.updateJobStatus(jobId, JobStatus.EXECUTING, null);

                jobExecutionService.execute(task);

                riseApiService.updateJobStatus(jobId, JobStatus.COMPLETED, null);
                System.out.printf("[JOBS] Completed | id=%s%n", jobId);

            } catch (Exception e) {
                System.err.println("[CRITICAL] Catching failure for Job " + jobId + ": " + e.getMessage());
                riseApiService.updateJobStatus(jobId, JobStatus.ERROR, e.getMessage(), true);
            }
        }
    }
}
