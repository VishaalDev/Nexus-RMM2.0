package com.nexusrmm.health.model;

import java.time.Instant;
import java.util.List;

/** Compact dashboard view: one row per component, no findings or per-instance detail. */
public record HealthSummary(
        Instant generatedAt,
        Integer overallScore,
        HealthStatus overallStatus,
        double dataCoverage,
        Integer overallRiskScore,
        RiskLevel overallRiskLevel,
        Confidence riskConfidence,
        List<Row> components
) {
    public record Row(ComponentId component, String label, Integer score, HealthStatus status,
                      RiskLevel riskLevel, String noDataReason) {
    }

    public HealthSummary {
        components = components == null ? List.of() : List.copyOf(components);
    }
}
