package com.nexusrmm.health.web;

import com.nexusrmm.health.model.ComponentRisk;
import com.nexusrmm.health.model.RiskReport;
import com.nexusrmm.health.service.HealthFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RiskController {

    private final HealthFacade facade;

    public RiskController(HealthFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/risk")
    public RiskReport risk() {
        return facade.riskReport();
    }

    @GetMapping("/api/risk/{component}")
    public ComponentRisk componentRisk(@PathVariable("component") String component) {
        return facade.componentRisk(HealthAnalysisController.parse(component));
    }
}
