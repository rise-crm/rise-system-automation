package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import com.dariodussin.whatsappautomationbackend.model.JobStatus;
import com.dariodussin.whatsappautomationbackend.model.JobType;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import com.dariodussin.whatsappautomationbackend.service.JobExecutionService;
import com.dariodussin.whatsappautomationbackend.service.JobPollingState;
import com.dariodussin.whatsappautomationbackend.service.RiseApiService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobRunnerTest {

    private final RiseApiService riseApiService = mock(RiseApiService.class);
    private final JobExecutionService jobExecutionService = mock(JobExecutionService.class);
    private final JobPollingState pollingState = mock(JobPollingState.class);
    private final JobRunner runner = new JobRunner(riseApiService, jobExecutionService, pollingState);

    @Test
    void disabledPollingSkipsFetch() {
        when(pollingState.isEnabled()).thenReturn(false);

        runner.runJobs();

        verify(riseApiService, never()).fetchPendingTasks();
        verifyNoInteractions(jobExecutionService);
    }

    @Test
    void enabledPollingFetchesTasks() {
        when(pollingState.isEnabled()).thenReturn(true);
        when(riseApiService.fetchPendingTasks()).thenReturn(List.of());

        runner.runJobs();

        verify(riseApiService).fetchPendingTasks();
    }

    @Test
    void claimFailureSkipsExecution() throws Exception {
        ScheduleJob job = sampleJob("job-1");
        when(pollingState.isEnabled()).thenReturn(true);
        when(riseApiService.fetchPendingTasks()).thenReturn(List.of(job));
        when(riseApiService.updateJobStatus(eq("job-1"), eq(JobStatus.EXECUTING), isNull())).thenReturn(false);

        runner.runJobs();

        verify(jobExecutionService, never()).execute(any());
        verify(riseApiService, never()).updateJobStatus(eq("job-1"), eq(JobStatus.COMPLETED), any());
    }

    @Test
    void successfulClaimExecutesJob() throws Exception {
        ScheduleJob job = sampleJob("job-2");
        when(pollingState.isEnabled()).thenReturn(true);
        when(riseApiService.fetchPendingTasks()).thenReturn(List.of(job));
        when(riseApiService.updateJobStatus(eq("job-2"), eq(JobStatus.EXECUTING), isNull())).thenReturn(true);
        when(riseApiService.updateJobStatus(eq("job-2"), eq(JobStatus.COMPLETED), isNull())).thenReturn(true);

        runner.runJobs();

        verify(jobExecutionService).execute(job);
        verify(riseApiService).updateJobStatus("job-2", JobStatus.COMPLETED, null);
    }

    private static ScheduleJob sampleJob(String id) {
        return new ScheduleJob(
                id,
                "camp-1",
                null,
                JobType.SEND_MESSAGE,
                new JobMetadata(null, null, null, null, "oi", null, false),
                "pending",
                null,
                null,
                null,
                null,
                null);
    }
}
