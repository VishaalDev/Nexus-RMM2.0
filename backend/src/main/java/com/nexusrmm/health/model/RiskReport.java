package com.nexusrmm.health.model;

import java.time.Instant;
import java.util.List;

public record RiskReport(
        Instant generatedAt,
        Integer overallRiskScore,
        RiskLevel overallLevel,
        Confidence confidence,
        List<ComponentRisk> components,
        String methodology
) {
    public RiskReport {
        components = components == null ? List.of() : List.copyOf(components);
    }

    public ComponentRisk component(ComponentId id) {
        return components.stream().filter(c -> c.component() == id).findFirst().orElse(null);
    }
}
