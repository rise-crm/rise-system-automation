package com.dariodussin.whatsappautomationbackend.service;

import com.dariodussin.whatsappautomationbackend.model.JobMetadata;
import com.dariodussin.whatsappautomationbackend.model.JobType;
import com.dariodussin.whatsappautomationbackend.model.ScheduleJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobExecutionServiceTest {

    @Mock
    private RiseApiService riseApiService;

    @Mock
    private EvolutionApiService evolutionApiService;

    @Test
    void skipsGroupsAlreadySentAndContinuesRemaining() throws Exception {
        InMemoryProgress progress = new InMemoryProgress();
        progress.markSent("job-1", "g1");
        JobExecutionService service = new JobExecutionService(
                riseApiService, evolutionApiService, progress, millis -> {});
        ScheduleJob job = sampleJob("job-1");
        when(riseApiService.getInstanceNameByCampaignId("camp-1")).thenReturn("inst-1");
        when(riseApiService.getGroupIdsByCampaignId("camp-1")).thenReturn(List.of("g1", "g2", "g3"));

        service.execute(job);

        verify(evolutionApiService, never()).sendTextMessage(eq("inst-1"), eq("g1"), any());
        verify(evolutionApiService).sendTextMessage(eq("inst-1"), eq("g2"), any());
        verify(evolutionApiService).sendTextMessage(eq("inst-1"), eq("g3"), any());
        assertTrue(progress.alreadySent("job-1").isEmpty());
    }

    @Test
    void partialFailureKeepsProgressForSentGroups() {
        InMemoryProgress progress = new InMemoryProgress();
        JobExecutionService service = new JobExecutionService(
                riseApiService, evolutionApiService, progress, millis -> {});
        ScheduleJob job = sampleJob("job-2");
        when(riseApiService.getInstanceNameByCampaignId("camp-1")).thenReturn("inst-1");
        when(riseApiService.getGroupIdsByCampaignId("camp-1")).thenReturn(List.of("g1", "g2"));
        doNothing().when(evolutionApiService).sendTextMessage(anyString(), anyString(), any());
        doThrow(new RuntimeException("boom"))
                .when(evolutionApiService).sendTextMessage(eq("inst-1"), eq("g2"), any());

        Exception error = assertThrows(Exception.class, () -> service.execute(job));

        assertTrue(error.getMessage().contains("g2"));
        verify(evolutionApiService).sendTextMessage(eq("inst-1"), eq("g1"), any());
        assertEquals(Set.of("g1"), progress.alreadySent("job-2"));
    }

    private static ScheduleJob sampleJob(String id) {
        return new ScheduleJob(
                id,
                "camp-1",
                null,
                JobType.SEND_MESSAGE,
                new JobMetadata(null, null, null, null, "oi", null, false),
                "executing",
                null,
                null,
                null,
                null,
                null);
    }

    private static final class InMemoryProgress implements JobSendProgress {
        private final Set<String> sent = new HashSet<>();

        @Override
        public Set<String> alreadySent(String jobId) {
            Set<String> result = new HashSet<>();
            String prefix = jobId + ":";
            for (String entry : sent) {
                if (entry.startsWith(prefix)) {
                    result.add(entry.substring(prefix.length()));
                }
            }
            return result;
        }

        @Override
        public void markSent(String jobId, String groupId) {
            sent.add(jobId + ":" + groupId);
        }

        @Override
        public void clear(String jobId) {
            sent.removeIf(entry -> entry.startsWith(jobId + ":"));
        }
    }
}
