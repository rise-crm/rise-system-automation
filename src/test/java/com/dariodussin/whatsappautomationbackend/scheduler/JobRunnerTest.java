package com.dariodussin.whatsappautomationbackend.scheduler;

import com.dariodussin.whatsappautomationbackend.service.JobExecutionService;
import com.dariodussin.whatsappautomationbackend.service.JobPollingState;
import com.dariodussin.whatsappautomationbackend.service.RiseApiService;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
