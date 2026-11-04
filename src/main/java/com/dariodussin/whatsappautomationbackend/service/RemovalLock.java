package com.dariodussin.whatsappautomationbackend.service;

public interface RemovalLock {

    /**
     * Claims the single removal request for a contact in a group.
     *
     * @return true when the caller should request the removal
     */
    boolean tryAcquire(String groupJid, String contactJid);

    void release(String groupJid, String contactJid);
}
