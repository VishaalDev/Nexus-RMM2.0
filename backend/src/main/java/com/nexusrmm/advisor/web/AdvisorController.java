package com.nexusrmm.advisor.web;

import com.nexusrmm.advisor.model.AdvisorReport;
import com.nexusrmm.advisor.model.AlertEvent;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.advisor.service.AdvisorFacade;
import com.nexusrmm.advisor.service.ProcessSamplerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Module 4 endpoints. All read-only GETs; the advisor never changes the computer. */
@RestController
public class AdvisorController {

    private final AdvisorFacade advisor;
    private final ProcessSamplerService processes;

    public AdvisorController(AdvisorFacade advisor, ProcessSamplerService processes) {
        this.advisor = advisor;
        this.processes = processes;
    }

    @GetMapping("/api/advisor")
    public AdvisorReport advisor() {
        return advisor.report();
    }

    @GetMapping("/api/advisor/alerts")
    public List<AlertEvent> alerts(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return advisor.alerts(limit);
    }

    @GetMapping("/api/processes")
    public ProcessSnapshot processes() {
        return processes.latest();
    }
}
