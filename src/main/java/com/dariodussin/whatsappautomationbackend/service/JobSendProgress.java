package com.dariodussin.whatsappautomationbackend.service;

import java.util.Set;

/**
 * Tracks which campaign groups already received a job send, so a retry after a
 * partial failure does not re-send to those groups.
 */
public interface JobSendProgress {

    Set<String> alreadySent(String jobId);

    void markSent(String jobId, String groupId);

    void clear(String jobId);
}
