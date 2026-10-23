package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 13: analysis engine aggregation. */
class HealthAnalysisEngineTest {

    private final HealthSettings settings = HealthSettings.defaults();
    private final HealthAnalysisEngine engine = new HealthAnalysisEngine(HealthAnalysisEngine.defaultAnalyzers(), settings);

    @Test
    void thereAreExactlyEightAnalyzersOneForEveryComponent() {
        List<ComponentAnalyzer> all = HealthAnalysisEngine.defaultAnalyzers();
        assertEquals(8, all.size());
        Set<ComponentId> ids = all.stream().map(ComponentAnalyzer::component).collect(Collectors.toCollection(() -> EnumSet.noneOf(ComponentId.class)));
        assertEquals(EnumSet.allOf(ComponentId.class), ids);
    }

    @Test
    void healthyMachineScoresFullMarksAndReportsHonestCoverage() {
        HealthReport r = engine.analyze(Scenarios.healthyWeek(), NOW);
        assertEquals(100, r.overallScore());
        assertEquals(HealthStatus.HEALTHY, r.overallStatus());
        // Scored: cpu 1 + mem 1 + storage 1.5 + thermal 1.5 + reliability 2 = 7 of total 8.75
        assertEquals(0.8, r.dataCoverage(), 1e-9);
        assertEquals(8, r.components().size());
        assertEquals("test-computer", r.computerId());
    }

    @Test
    void noDataComponentsAreExcludedNotCountedAsPerfectOrZero() {
        HealthReport r = engine.analyze(Scenarios.healthyWeek(), NOW);
        for (ComponentId id : List.of(ComponentId.BATTERY, ComponentId.NETWORK, ComponentId.STORAGE_IO)) {
            ComponentHealth c = r.component(id);
            assertEquals(HealthStatus.NO_DATA, c.status(), id.name());
            assertNull(c.score());
        }
        assertTrue(r.limitations().stream().anyMatch(l -> l.startsWith("Battery not scored")));
    }

    @Test
    void aSingleFailingComponentCannotBeAveragedAway() {
        FakeHealthData pinned = new FakeHealthData()
                .recentConstant(MetricType.CPU_UTILIZATION, null, 95)
                .recentConstant(MetricType.RAM_UTILIZATION, null, 50)
                .recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 50)
                .recentConstant(MetricType.CPU_TEMPERATURE, null, 55);
        HealthReport r = engine.analyze(pinned, NOW);
        assertEquals(5, r.component(ComponentId.CPU).score());
        // weighted mean = (5*1 + 100*(1+1.5+1.5+2)) / 7 = 86.4, but capped at 5 + 30 = 35
        assertEquals(35, r.overallScore());
        assertEquals(HealthStatus.CRITICAL, r.overallStatus());
        assertTrue(r.overallBasis().contains("capped"));
    }

    @Test
    void withNothingToScoreTheOverallIsNoDataWithNoScore() {
        HealthReport r = engine.analyze(new FakeHealthData().monitoring(
                new com.nexusrmm.health.provider.EventMonitoring(false, false, NOW, "n/a")), NOW);
        assertNull(r.overallScore());
        assertEquals(HealthStatus.NO_DATA, r.overallStatus());
        assertEquals(0.0, r.dataCoverage(), 1e-9);
        assertEquals("No component had enough data to be scored.", r.overallBasis());
    }

    @Test
    void anAnalyzerThatThrowsIsIsolatedAndTheOthersStillReport() {
        ComponentAnalyzer boom = new ComponentAnalyzer() {
            @Override public ComponentId component() { return ComponentId.CPU; }
            @Override public ComponentHealth analyze(AnalysisContext context) { throw new IllegalStateException("kaboom"); }
        };
        List<ComponentAnalyzer> analyzers = new ArrayList<>(HealthAnalysisEngine.defaultAnalyzers());
        analyzers.removeIf(a -> a.component() == ComponentId.CPU);
        analyzers.add(boom);

        HealthReport r = new HealthAnalysisEngine(analyzers, settings).analyze(Scenarios.healthyWeek(), NOW);
        ComponentHealth cpu = r.component(ComponentId.CPU);
        assertEquals(HealthStatus.NO_DATA, cpu.status());
        assertTrue(cpu.noDataReason().contains("IllegalStateException"));
        assertEquals(100, r.component(ComponentId.MEMORY).score());
        assertEquals(100, r.overallScore());
    }

    @Test
    void componentsAreReturnedInStableEnumOrder() {
        HealthReport r = engine.analyze(Scenarios.healthyWeek(), NOW);
        assertEquals(List.of(ComponentId.values()), r.components().stream().map(ComponentHealth::component).toList());
    }

    @Test
    void staticLimitationsAlwaysStateWhatIsNotMeasured() {
        HealthReport r = engine.analyze(Scenarios.healthyWeek(), NOW);
        String all = String.join("\n", r.limitations());
        assertTrue(all.contains("GPU"));
        assertTrue(all.contains("SMART"));
        assertTrue(all.contains("not calibrated"));
    }
}
