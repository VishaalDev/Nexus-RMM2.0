package com.nexusrmm.health.model;

import java.time.Instant;
import java.util.List;

/**
 * @param dataCoverage fraction of the total component weight that could be
 *                     assessed (1.0 = every component scored; 0.5 = half the
 *                     weight had no usable data). It is a statement about
 *                     the report, not about the machine.
 * @param limitations  plain-language list of what this report cannot see.
 */
public record HealthReport(
        Instant generatedAt,
        String computerId,
        Integer overallScore,
        HealthStatus overallStatus,
        double dataCoverage,
        String overallBasis,
        List<ComponentHealth> components,
        List<String> limitations
) {
    public HealthReport {
        components = components == null ? List.of() : List.copyOf(components);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public ComponentHealth component(ComponentId id) {
        return components.stream().filter(c -> c.component() == id).findFirst().orElse(null);
    }
}
