package com.dariodussin.whatsappautomationbackend.service;

public interface SpamTracker {

    /**
     * Records one message in the sender's one-minute window.
     *
     * @return true when that sender has 10 distinct messages in the window
     */
    boolean record(String groupJid, String contactJid, String messageId);
}
