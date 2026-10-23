package com.nexusrmm.health.model;

import java.util.List;

/**
 * @param horizonDays when a factor implies a time-to-event (e.g. a volume
 *                    projected to fill), the shortest such estimate in
 *                    days; otherwise null.
 */
public record ComponentRisk(
        ComponentId component,
        String label,
        Integer riskScore,
        RiskLevel level,
        Confidence confidence,
        Double horizonDays,
        List<RiskFactor> factors,
        String notAssessedReason
) {
    public ComponentRisk {
        factors = factors == null ? List.of() : List.copyOf(factors);
    }
}
