package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.service.JobPollingState;
import com.dariodussin.whatsappautomationbackend.service.RiseApiService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Requeues jobs left in {@code executing} after a crash or deploy so they are not stuck forever.
 * Already-sent groups are skipped via {@link com.dariodussin.whatsappautomationbackend.service.JobSendProgress}.
 */
@Component
public class JobStuckRecoveryRunner {

    private final RiseApiService riseApiService;
    private final JobPollingState pollingState;
    private final int abandonedAfterMinutes;

    public JobStuckRecoveryRunner(RiseApiService riseApiService,
                                  JobPollingState pollingState,
                                  @Value("${jobs.abandoned-executing-minutes:15}") int abandonedAfterMinutes) {
        this.riseApiService = riseApiService;
        this.pollingState = pollingState;
        this.abandonedAfterMinutes = abandonedAfterMinutes;
    }

    @Scheduled(fixedDelayString = "${jobs.stuck-recovery-delay-ms:60000}")
    public void recoverAbandoned() {
        if (!pollingState.isEnabled()) {
            return;
        }
        List<ScheduleJob> abandoned;
        try {
            abandoned = riseApiService.fetchAbandonedExecutingTasks(abandonedAfterMinutes);
        } catch (Exception e) {
            System.err.printf("[JOBS] Stuck recovery poll failed | error=%s%n", e.getClass().getSimpleName());
            return;
        }
        if (abandoned == null || abandoned.isEmpty()) {
            return;
        }

        System.out.printf("[JOBS] Requeueing %d abandoned executing job(s)%n", abandoned.size());
        for (ScheduleJob job : abandoned) {
            String jobId = job.id();
            String reason = "Abandoned executing after " + abandonedAfterMinutes + "m; requeued";
            if (riseApiService.updateJobStatus(jobId, JobStatus.PENDING, reason)) {
                System.out.printf("[JOBS] Requeued abandoned job | id=%s%n", jobId);
            } else {
                System.err.printf("[JOBS] Failed to requeue abandoned job | id=%s%n", jobId);
            }
        }
    }
}
