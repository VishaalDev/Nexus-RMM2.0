package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.support.FakeHealthData;
import com.nexusrmm.provider.AvailabilityStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 5: CPU analyzer. */
class CpuAnalyzerTest {

    private final CpuAnalyzer analyzer = new CpuAnalyzer();

    private ComponentHealth run(FakeHealthData d) {
        return analyzer.analyze(ctx(d));
    }

    @Test
    void idleCpuIsHealthyWithNoFindings() {
        ComponentHealth c = run(new FakeHealthData().recentConstant(MetricType.CPU_UTILIZATION, null, 30));
        assertEquals(100, c.score());
        assertEquals(HealthStatus.HEALTHY, c.status());
        assertTrue(c.findings().isEmpty());
        assertEquals(1.0, c.dataCoverage(), 1e-9, "360 of 360 expected samples");
    }

    @Test
    void constantEightyPercentScoresFairByTheDocumentedCurve() {
        // curve(80) = 25 + (5/15)*25 = 33.33; no saturation -> 66.67 -> 67
        ComponentHealth c = run(new FakeHealthData().recentConstant(MetricType.CPU_UTILIZATION, null, 80));
        assertEquals(67, c.score());
        assertEquals(HealthStatus.FAIR, c.status());
        assertTrue(hasFinding(c, "CPU_SUSTAINED_HIGH"));
        assertFalse(hasFinding(c, "CPU_SATURATED"));
    }

    @Test
    void pinnedCpuIsCriticalWithSaturationFinding() {
        // curve(95)=55 + saturation ramp 40 -> penalty 95 -> score 5
        ComponentHealth c = run(new FakeHealthData().recentConstant(MetricType.CPU_UTILIZATION, null, 95));
        assertEquals(5, c.score());
        assertEquals(HealthStatus.CRITICAL, c.status());
        assertTrue(hasFinding(c, "CPU_SUSTAINED_CRITICAL"));
        assertTrue(hasFinding(c, "CPU_SATURATED"));
    }

    @Test
    void aBriefSpikeInAnOtherwiseIdleHourBarelyMovesTheScore() {
        FakeHealthData d = new FakeHealthData().values(MetricType.CPU_UTILIZATION, null, NOW.minus(Duration.ofHours(1)), NOW,
                FakeHealthData.STEP, t -> Duration.between(t, NOW).toSeconds() <= 20 ? 100 : 20);
        ComponentHealth c = run(d);
        assertTrue(c.score() >= 95, "2 of 360 samples at 100% must not register as a problem, got " + c.score());
    }

    @Test
    void tooFewSamplesIsNoDataNotAGoodScore() {
        FakeHealthData d = new FakeHealthData().values(MetricType.CPU_UTILIZATION, null, NOW.minus(Duration.ofSeconds(50)), NOW,
                FakeHealthData.STEP, t -> 10);
        ComponentHealth c = run(d);
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertNull(c.score());
        assertTrue(c.noDataReason().contains("Insufficient data: 5"), c.noDataReason());
    }

    @Test
    void anUnavailableSensorSurfacesTheProvidersOwnDiagnostic() {
        FakeHealthData d = new FakeHealthData().valueless(MetricType.CPU_UTILIZATION, null, NOW.minus(Duration.ofHours(1)), NOW,
                FakeHealthData.STEP, AvailabilityStatus.UNAVAILABLE, "Establishing CPU load baseline");
        ComponentHealth c = run(d);
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertTrue(c.noDataReason().contains("Establishing CPU load baseline"), c.noDataReason());
    }

    @Test
    void aStoppedCollectorIsExplainedWithTheLastSampleTime() {
        FakeHealthData d = new FakeHealthData().values(MetricType.CPU_UTILIZATION, null, NOW.minus(Duration.ofDays(2)),
                NOW.minus(Duration.ofDays(2)).plus(Duration.ofMinutes(10)), FakeHealthData.STEP, t -> 10);
        ComponentHealth c = run(d);
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertTrue(c.noDataReason().contains("collector stopped"), c.noDataReason());
        assertTrue(c.noDataReason().contains("2026-10-19"), "latest stored sample time must be reported: " + c.noDataReason());
    }

    @Test
    void neverStoredAnythingIsReportedAsSuch() {
        ComponentHealth c = run(new FakeHealthData());
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertTrue(c.noDataReason().contains("No CPU utilization samples found in the last 7 days"), c.noDataReason());
    }
}
