package com.nexusrmm.health.model;

import java.util.List;

/**
 * Result of one analyzer. {@code basis} states in one line exactly what the
 * score is computed from (and what it is NOT), so a number is never
 * presented without its provenance. {@code weight} is the share this
 * component carries in the overall score.
 */
public record ComponentHealth(
        ComponentId component,
        String label,
        Integer score,
        HealthStatus status,
        double weight,
        int sampleCount,
        double dataCoverage,
        String basis,
        List<InstanceHealth> instances,
        List<Finding> findings,
        String noDataReason
) {
    public ComponentHealth {
        instances = instances == null ? List.of() : List.copyOf(instances);
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public boolean hasScore() {
        return score != null;
    }

    public static ComponentHealth noData(ComponentId id, double weight, String basis, String reason,
                                          List<InstanceHealth> instances) {
        return new ComponentHealth(id, id.label(), null, HealthStatus.NO_DATA, weight, 0, 0.0, basis,
                instances, List.of(), reason);
    }
}
