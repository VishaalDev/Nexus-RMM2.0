package com.nexusrmm.health.service;

import com.nexusrmm.health.analysis.HealthAnalysisEngine;
import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.ComponentRisk;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.HealthSummary;
import com.nexusrmm.health.model.RiskReport;
import com.nexusrmm.health.provider.HealthDataProvider;
import com.nexusrmm.health.risk.RiskEngine;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Single entry point the controllers use. Health and risk are computed
 * from the same snapshot (so a summary can never pair a health score with
 * a risk score from a different moment) and cached for
 * {@code nexus.health.cache-ttl-seconds}: the UI polls, and each analysis
 * reads up to a week of bucketed history, so recomputing per request
 * would be wasteful. Framework-free; wired as a bean by HealthModuleConfig.
 */
public class HealthFacade {

    private record Snapshot(HealthReport health, RiskReport risk, Instant computedAt) {
    }

    private final HealthDataProvider provider;
    private final HealthAnalysisEngine engine;
    private final RiskEngine riskEngine;
    private final HealthSettings settings;
    private final Clock clock;

    private Snapshot cached;

    public HealthFacade(HealthDataProvider provider, HealthAnalysisEngine engine, RiskEngine riskEngine,
                        HealthSettings settings, Clock clock) {
        this.provider = provider;
        this.engine = engine;
        this.riskEngine = riskEngine;
        this.settings = settings;
        this.clock = clock;
    }

    private synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.computedAt().plus(settings.cacheTtl()))) {
            return cached;
        }
        HealthReport health = engine.analyze(provider, now);
        RiskReport risk = riskEngine.assess(health, now);
        cached = new Snapshot(health, risk, now);
        return cached;
    }

    public HealthReport healthReport() {
        return snapshot().health();
    }

    public List<ComponentHealth> components() {
        return snapshot().health().components();
    }

    public ComponentHealth component(ComponentId id) {
        return snapshot().health().component(id);
    }

    public RiskReport riskReport() {
        return snapshot().risk();
    }

    public ComponentRisk componentRisk(ComponentId id) {
        return snapshot().risk().component(id);
    }

    public HealthSummary summary() {
        Snapshot s = snapshot();
        List<HealthSummary.Row> rows = s.health().components().stream().map(c -> {
            ComponentRisk r = s.risk().component(c.component());
            return new HealthSummary.Row(c.component(), c.label(), c.score(), c.status(),
                    r == null ? null : r.level(), c.noDataReason());
        }).toList();
        return new HealthSummary(s.health().generatedAt(), s.health().overallScore(), s.health().overallStatus(),
                s.health().dataCoverage(), s.risk().overallRiskScore(), s.risk().overallLevel(), s.risk().confidence(), rows);
    }
}
