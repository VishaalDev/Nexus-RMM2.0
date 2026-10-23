package com.nexusrmm.health.service;

import com.nexusrmm.health.analysis.AnalysisContext;
import com.nexusrmm.health.analysis.ComponentAnalyzer;
import com.nexusrmm.health.analysis.HealthAnalysisEngine;
import com.nexusrmm.health.analysis.Scenarios;
import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.model.HealthSummary;
import com.nexusrmm.health.risk.RiskEngine;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 15: facade (snapshot consistency, caching, summary). */
class HealthFacadeTest {

    /** A clock the test can advance. */
    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final HealthSettings settings = HealthSettings.defaults(); // cache TTL = 10 s
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger runs = new AtomicInteger();

    private HealthFacade facade() {
        List<ComponentAnalyzer> analyzers = new ArrayList<>();
        for (ComponentId id : ComponentId.values()) {
            analyzers.add(new ComponentAnalyzer() {
                @Override public ComponentId component() { return id; }
                @Override public ComponentHealth analyze(AnalysisContext c) {
                    runs.incrementAndGet();
                    return new ComponentHealth(id, id.label(), 90, HealthStatus.HEALTHY, 1.0, 10, 1.0, "test", List.of(), List.of(), null);
                }
            });
        }
        return new HealthFacade(Scenarios.healthyWeek(), new HealthAnalysisEngine(analyzers, settings), new RiskEngine(settings), settings, clock);
    }

    @Test
    void repeatedCallsInsideTheTtlReuseOneAnalysis() {
        HealthFacade f = facade();
        f.healthReport();
        f.riskReport();
        f.summary();
        f.component(ComponentId.CPU);
        assertEquals(8, runs.get(), "8 analyzers, run once");
    }

    @Test
    void afterTheTtlTheAnalysisIsRecomputed() {
        HealthFacade f = facade();
        f.healthReport();
        clock.advance(Duration.ofSeconds(9));
        f.healthReport();
        assertEquals(8, runs.get());
        clock.advance(Duration.ofSeconds(2));
        f.healthReport();
        assertEquals(16, runs.get());
    }

    @Test
    void healthAndRiskInASummaryComeFromTheSameMoment() {
        HealthFacade f = facade();
        HealthSummary s = f.summary();
        assertEquals(f.healthReport().generatedAt(), s.generatedAt());
        assertEquals(f.riskReport().generatedAt(), s.generatedAt());
        assertSame(f.healthReport(), f.healthReport(), "cached snapshot is shared");
    }

    @Test
    void summaryHasOneRowPerComponentCarryingScoreStatusAndRiskLevel() {
        HealthSummary s = facade().summary();
        assertEquals(8, s.components().size());
        HealthSummary.Row cpu = s.components().stream().filter(r -> r.component() == ComponentId.CPU).findFirst().orElseThrow();
        assertEquals(90, cpu.score());
        assertEquals(HealthStatus.HEALTHY, cpu.status());
        assertNotNull(cpu.riskLevel());
        assertEquals(90, s.overallScore());
    }

    @Test
    void componentAndRiskLookupsReturnTheRequestedComponent() {
        HealthFacade f = facade();
        assertEquals(ComponentId.THERMAL, f.component(ComponentId.THERMAL).component());
        assertEquals(ComponentId.THERMAL, f.componentRisk(ComponentId.THERMAL).component());
        assertTrue(f.components().size() == 8);
    }
}
