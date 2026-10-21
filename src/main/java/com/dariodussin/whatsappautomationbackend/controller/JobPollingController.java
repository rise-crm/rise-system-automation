package com.dariodussin.whatsappautomationbackend.controller;

import com.dariodussin.whatsappautomationbackend.dto.PollingStatusResponse;
import com.dariodussin.whatsappautomationbackend.service.JobPollingState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/polling")
public class JobPollingController {

    private final JobPollingState pollingState;

    public JobPollingController(JobPollingState pollingState) {
        this.pollingState = pollingState;
    }

    @GetMapping
    public PollingStatusResponse status() {
        return new PollingStatusResponse(pollingState.isEnabled());
    }

    @PostMapping("/toggle")
    public PollingStatusResponse toggle() {
        boolean enabled = pollingState.toggle();
        System.out.printf("[JOBS] Polling toggled | enabled=%s%n", enabled);
        return new PollingStatusResponse(enabled);
    }
}
