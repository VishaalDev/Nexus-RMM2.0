package com.nexusrmm.health.model;

import java.util.List;
import java.util.Map;

/**
 * Health of one hardware instance (a volume, a physical disk, an adapter, a
 * power source) or of the single instance of a single-instance component
 * ({@code instance} is null then). {@code score} is null exactly when
 * {@code status} is NO_DATA, and {@code noDataReason} then says why.
 */
public record InstanceHealth(
        String instance,
        Integer score,
        HealthStatus status,
        int sampleCount,
        double dataCoverage,
        Map<String, Double> metrics,
        List<Finding> findings,
        String noDataReason
) {
    public InstanceHealth {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static InstanceHealth noData(String instance, String reason, int sampleCount, double coverage) {
        return new InstanceHealth(instance, null, HealthStatus.NO_DATA, sampleCount, coverage, Map.of(), List.of(), reason);
    }
}
