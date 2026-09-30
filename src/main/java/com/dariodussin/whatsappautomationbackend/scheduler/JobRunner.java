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
        List<ScheduleJob> tasks = riseApiService.fetchPendingTasks();
        if (tasks == null || tasks.isEmpty()) return;

        for (ScheduleJob task : tasks) {
            String jobId = task.id();
            try {
                riseApiService.updateJobStatus(jobId, JobStatus.EXECUTING, null);

                jobExecutionService.execute(task);

                riseApiService.updateJobStatus(jobId, JobStatus.COMPLETED, null);

            } catch (Exception e) {
                System.err.println("[CRITICAL] Catching failure for Job " + jobId + ": " + e.getMessage());
                riseApiService.updateJobStatus(jobId, JobStatus.ERROR, e.getMessage(), true);
            }
        }
    }
}
