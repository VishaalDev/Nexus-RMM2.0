package com.nexusrmm.health.web;

import com.nexusrmm.exception.BadRequestException;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.HealthSummary;
import com.nexusrmm.health.service.HealthFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Module 2 health endpoints. Deliberately a different class and sub-paths
 * from Module 1's liveness {@code GET /api/health}, which is unchanged.
 */
@RestController
public class HealthAnalysisController {

    private final HealthFacade facade;

    public HealthAnalysisController(HealthFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/api/health/summary")
    public HealthSummary summary() {
        return facade.summary();
    }

    @GetMapping("/api/health/report")
    public HealthReport report() {
        return facade.healthReport();
    }

    @GetMapping("/api/health/components")
    public List<ComponentHealth> components() {
        return facade.components();
    }

    @GetMapping("/api/health/components/{component}")
    public ComponentHealth component(@PathVariable("component") String component) {
        return facade.component(parse(component));
    }

    static ComponentId parse(String raw) {
        try {
            return ComponentId.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
