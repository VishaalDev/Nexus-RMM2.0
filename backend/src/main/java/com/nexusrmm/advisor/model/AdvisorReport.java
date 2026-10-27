package com.nexusrmm.advisor.model;

import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.model.RiskLevel;

import java.time.Instant;
import java.util.List;

/**
 * Everything the Advisor page shows, from one consistent evaluation.
 *
 * @param headline        one honest sentence; never says "healthy" when little could be assessed
 * @param healthCoverage  fraction of the health score that could be assessed (see HealthReport)
 * @param processInsight  whether per-process attribution was available for this evaluation
 * @param limitations     plain-language list of what this report cannot see
 */
public record AdvisorReport(
        Instant generatedAt,
        String headline,
        Integer healthScore,
        HealthStatus healthStatus,
        double healthCoverage,
        RiskLevel riskLevel,
        int criticalCount,
        int warningCount,
        int infoCount,
        boolean processInsight,
        List<Issue> issues,
        List<Action> topActions,
        List<String> limitations
) {
    public AdvisorReport {
        issues = issues == null ? List.of() : List.copyOf(issues);
        topActions = topActions == null ? List.of() : List.copyOf(topActions);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
