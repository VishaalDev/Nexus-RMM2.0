package com.nexusrmm.health.risk;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.analysis.HealthAnalysisEngine;
import com.nexusrmm.health.analysis.Scenarios;
import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.ComponentRisk;
import com.nexusrmm.health.model.Confidence;
import com.nexusrmm.health.model.RiskFactor;
import com.nexusrmm.health.model.RiskLevel;
import com.nexusrmm.health.model.RiskReport;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 14: risk engine. */
class RiskEngineTest {

    private final HealthSettings settings = HealthSettings.defaults();
    private final HealthAnalysisEngine health = new HealthAnalysisEngine(HealthAnalysisEngine.defaultAnalyzers(), settings);
    private final RiskEngine risk = new RiskEngine(settings);

    private RiskReport assess(FakeHealthData d) {
        return risk.assess(health.analyze(d, NOW), NOW);
    }

    private static boolean has(ComponentRisk c, String code) {
        return c.factors().stream().anyMatch(f -> f.code().equals(code));
    }

    private static RiskFactor factor(ComponentRisk c, String code) {
        return c.factors().stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void aHealthyMachineIsLowRiskAndMethodologyStatesItIsNotAProbability() {
        RiskReport r = assess(Scenarios.healthyWeek());
        assertEquals(0, r.overallRiskScore());
        assertEquals(RiskLevel.LOW, r.overallLevel());
        assertTrue(r.methodology().contains("not a probability"));
        assertEquals(RiskLevel.LOW, r.component(ComponentId.CPU).level());
    }

    @Test
    void componentsWithoutEvidenceAreNotAssessedNeverLow() {
        RiskReport r = assess(Scenarios.healthyWeek());
        ComponentRisk battery = r.component(ComponentId.BATTERY);
        assertEquals(RiskLevel.NOT_ASSESSED, battery.level());
        assertNull(battery.riskScore());
        assertNotNull(battery.notAssessedReason());
        assertEquals(RiskLevel.NOT_ASSESSED, r.component(ComponentId.NETWORK).level());
        assertTrue(r.component(ComponentId.NETWORK).notAssessedReason().contains("not failure indicators"));
        assertEquals(RiskLevel.NOT_ASSESSED, r.component(ComponentId.STORAGE_IO).level());
    }

    @Test
    void withNoDataAtAllTheOverallRiskIsNotAssessed() {
        RiskReport r = assess(new FakeHealthData().monitoring(new com.nexusrmm.health.provider.EventMonitoring(false, false, NOW, "n/a")));
        assertEquals(RiskLevel.NOT_ASSESSED, r.overallLevel());
        assertNull(r.overallRiskScore());
    }

    @Test
    void aNearlyFullVolumeIsCriticalRisk() {
        FakeHealthData d = new FakeHealthData().recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 97);
        ComponentRisk c = assess(d).component(ComponentId.STORAGE_CAPACITY);
        // severity = 0.70 + (2/5)*0.30 = 0.82 -> 82
        assertEquals(82, c.riskScore());
        assertEquals(RiskLevel.CRITICAL, c.level());
        assertTrue(has(c, "STORAGE_NEAR_FULL"));
    }

    @Test
    void aFillingVolumeReportsTheShortestHorizon() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.STORAGE_UTILIZATION, "C:\\", 7, Duration.ofMinutes(5), t -> 40 + 5 * (FakeHealthData.hours(NOW.minus(Duration.ofDays(7)), t) / 24.0))
                .recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 75);
        ComponentRisk c = assess(d).component(ComponentId.STORAGE_CAPACITY);
        assertTrue(has(c, "STORAGE_FILLING"));
        assertNotNull(c.horizonDays());
        assertEquals(5.0, c.horizonDays(), 0.5);
        assertEquals(0.80, factor(c, "STORAGE_FILLING").severity(), 0.02, "at/under the 7-day critical horizon the severity is 0.80");
    }

    @Test
    void sustainedHeatIsHighRiskWithTheDocumentedSeverity() {
        ComponentRisk c = assess(new FakeHealthData().recentConstant(MetricType.CPU_TEMPERATURE, null, 85)).component(ComponentId.THERMAL);
        // 100% of samples above warn: ramp(1.0, 0.10, 0.80, 0.60) = 0.60 -> 60
        assertEquals(60, c.riskScore());
        assertEquals(RiskLevel.HIGH, c.level());
    }

    @Test
    void criticalTemperatureExcursionsAddASeparateFactor() {
        ComponentRisk c = assess(new FakeHealthData().recentConstant(MetricType.CPU_TEMPERATURE, null, 95)).component(ComponentId.THERMAL);
        assertTrue(has(c, "CRITICAL_TEMPERATURE"));
        assertTrue(has(c, "SUSTAINED_HEAT"));
        assertEquals(RiskLevel.CRITICAL, c.level());
    }

    @Test
    void factorsCombineAsOneMinusProductOfComplements() {
        FakeHealthData d = new FakeHealthData().event(Duration.ofHours(30), "HARDWARE", "Error").event(Duration.ofHours(30), "STORAGE", "Error");
        ComponentRisk c = assess(d).component(ComponentId.RELIABILITY);
        // HARDWARE_ERRORS 0.5, STORAGE_ERRORS 0.4 -> 1 - 0.5*0.6 = 0.70
        assertEquals(0.5, factor(c, "HARDWARE_ERRORS").severity(), 1e-9);
        assertEquals(0.4, factor(c, "STORAGE_ERRORS").severity(), 1e-9);
        assertEquals(70, c.riskScore());
        assertEquals(RiskLevel.HIGH, c.level());
        assertEquals("HARDWARE_ERRORS", c.factors().get(0).code(), "strongest factor first");
    }

    @Test
    void anEventBurstInTheLastDayAgainstAQuietWeekIsEscalation() {
        FakeHealthData d = new FakeHealthData();
        for (int i = 0; i < 4; i++) d.event(Duration.ofHours(1 + i), "DRIVER", "Error");
        assertTrue(has(assess(d).component(ComponentId.RELIABILITY), "EVENT_ESCALATION"));
    }

    @Test
    void overallRiskCombinesWeightedComponentsAndIsDominatedByTheWorst() {
        FakeHealthData d = new FakeHealthData()
                .recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 97)
                .recentConstant(MetricType.CPU_UTILIZATION, null, 30)
                .recentConstant(MetricType.CPU_TEMPERATURE, null, 55);
        RiskReport r = assess(d);
        // storage risk 82 x weight 0.9 = 0.738; others 0 -> overall = 74
        assertEquals(74, r.overallRiskScore());
        assertEquals(RiskLevel.CRITICAL, r.overallLevel());
    }

    @Test
    void sparseHistoryCapsConfidenceAtLowEvenWhenTheSpanIsLong() {
        FakeHealthData d = new FakeHealthData();
        for (MetricType type : new MetricType[] { MetricType.CPU_UTILIZATION, MetricType.RAM_UTILIZATION, MetricType.CPU_TEMPERATURE }) {
            for (int daysBack : new int[] { 7, 4, 1 }) {
                java.time.Instant from = NOW.minus(Duration.ofDays(daysBack));
                d.values(type, null, from, from.plus(Duration.ofHours(2)), Duration.ofMinutes(5), t -> 40);
            }
            d.recentConstant(type, null, 40);
        }
        d.recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 50);
        assertEquals(Confidence.LOW, assess(d).confidence(), "a 7-day span made of three 2-hour sessions is not 7 days of evidence");
    }

    @Test
    void confidenceReflectsHistoryAndCoverageNotTheRiskItself() {
        assertEquals(Confidence.HIGH, assess(Scenarios.healthyWeek()).confidence());
        assertEquals(Confidence.LOW, assess(Scenarios.justOneHour()).confidence());

        FakeHealthData twoDays = new FakeHealthData()
                .history(MetricType.CPU_UTILIZATION, null, 2, Duration.ofMinutes(5), t -> 30).recentConstant(MetricType.CPU_UTILIZATION, null, 30)
                .history(MetricType.RAM_UTILIZATION, null, 2, Duration.ofMinutes(5), t -> 50).recentConstant(MetricType.RAM_UTILIZATION, null, 50)
                .history(MetricType.STORAGE_UTILIZATION, "C:\\", 2, Duration.ofMinutes(5), t -> 50).recentConstant(MetricType.STORAGE_UTILIZATION, "C:\\", 50)
                .history(MetricType.CPU_TEMPERATURE, null, 2, Duration.ofMinutes(5), t -> 55).recentConstant(MetricType.CPU_TEMPERATURE, null, 55);
        assertEquals(Confidence.MEDIUM, assess(twoDays).confidence());
    }
}
