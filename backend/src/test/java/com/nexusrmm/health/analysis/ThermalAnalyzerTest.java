package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.support.FakeHealthData;
import com.nexusrmm.provider.AvailabilityStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.analysis.TestSupport.FIVE_MIN;
import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static com.nexusrmm.health.analysis.TestSupport.linearPerDay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 9: thermal analyzer. */
class ThermalAnalyzerTest {

    private final ThermalAnalyzer analyzer = new ThermalAnalyzer();

    private ComponentHealth at(double celsius) {
        return analyzer.analyze(ctx(new FakeHealthData().recentConstant(MetricType.CPU_TEMPERATURE, null, celsius)));
    }

    @Test
    void coolCpuIsHealthy() {
        assertEquals(100, at(60).score());
        assertEquals(HealthStatus.HEALTHY, at(60).status());
    }

    @Test
    void warmCpuFollowsTheCurve() {
        // p95=85: 25 + (5/10)*35 = 42.5 -> 57.5 -> 58
        ComponentHealth c = at(85);
        assertEquals(58, c.score());
        assertTrue(hasFinding(c, "THERMAL_HIGH"));
    }

    @Test
    void criticalTemperatureIsCriticalAndFlagsExcursions() {
        // p95=95: 60 + (5/15)*40 = 73.33 -> 26.67 -> 27
        ComponentHealth c = at(95);
        assertEquals(27, c.score());
        assertEquals(HealthStatus.CRITICAL, c.status());
        assertTrue(hasFinding(c, "THERMAL_CRITICAL"));
        assertTrue(hasFinding(c, "THERMAL_CRITICAL_EXCURSIONS"));
    }

    @Test
    void oneSensorBlipDoesNotDominateBecauseP95IsUsed() {
        FakeHealthData d = new FakeHealthData().values(MetricType.CPU_TEMPERATURE, null, NOW.minus(Duration.ofHours(1)), NOW,
                FakeHealthData.STEP, t -> Duration.between(t, NOW).toSeconds() == 10 ? 127 : 55);
        assertEquals(100, analyzer.analyze(ctx(d)).score());
    }

    @Test
    void anUnsupportedSensorIsNoDataWithTheProvidersDiagnosticNeverAGoodScore() {
        FakeHealthData d = new FakeHealthData().valueless(MetricType.CPU_TEMPERATURE, null, NOW.minus(Duration.ofHours(1)), NOW,
                FakeHealthData.STEP, AvailabilityStatus.UNSUPPORTED, "PawnIO driver not installed");
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertNull(c.score());
        assertTrue(c.noDataReason().contains("PawnIO driver not installed"), c.noDataReason());
    }

    @Test
    void aRisingBaselineIsInformationalAndLabelledAsNotLoadAdjusted() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.CPU_TEMPERATURE, null, 7, FIVE_MIN, linearPerDay(45, 1.0))
                .recentConstant(MetricType.CPU_TEMPERATURE, null, 52);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertTrue(hasFinding(c, "THERMAL_RISING_TREND"));
        assertTrue(TestSupport.finding(c, "THERMAL_RISING_TREND").message().contains("not adjusted for workload"));
        assertEquals(100, c.score());
    }
}
