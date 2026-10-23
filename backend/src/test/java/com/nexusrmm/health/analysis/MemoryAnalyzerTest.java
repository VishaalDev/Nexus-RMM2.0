package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import static com.nexusrmm.health.analysis.TestSupport.FIVE_MIN;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.finding;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static com.nexusrmm.health.analysis.TestSupport.linearPerDay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;

/** Backend test area 6: memory analyzer (shares UtilizationAnalyzer with CPU but has its own thresholds). */
class MemoryAnalyzerTest {

    private final MemoryAnalyzer analyzer = new MemoryAnalyzer();

    @Test
    void usesMemoryThresholdsNotCpuThresholds() {
        // curve(70) with memory warn 80: (60,0)->(80,25) => 12.5 -> 88 HEALTHY. (The CPU curve would give a lower score.)
        ComponentHealth c = analyzer.analyze(ctx(new FakeHealthData().recentConstant(MetricType.RAM_UTILIZATION, null, 70)));
        assertEquals(88, c.score());
        assertEquals(HealthStatus.HEALTHY, c.status());
    }

    @Test
    void highSustainedUsageIsDegraded() {
        // curve(88) = 25 + (8/12)*25 = 41.67 -> 58.33 -> 58
        ComponentHealth c = analyzer.analyze(ctx(new FakeHealthData().recentConstant(MetricType.RAM_UTILIZATION, null, 88)));
        assertEquals(58, c.score());
        assertEquals(HealthStatus.DEGRADED, c.status());
        assertTrue(hasFinding(c, "MEMORY_SUSTAINED_HIGH"));
    }

    @Test
    void aSignificantRisingTrendIsReportedWithItsStatisticsButDoesNotChangeTheScore() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.RAM_UTILIZATION, null, 7, FIVE_MIN, linearPerDay(40, 3))
                .recentConstant(MetricType.RAM_UTILIZATION, null, 61);
        ComponentHealth c = analyzer.analyze(ctx(d));
        var f = finding(c, "MEMORY_RISING_TREND");
        assertEquals(3.0, (Double) f.evidence().get("slopePerDay"), 0.15);
        assertTrue((Double) f.evidence().get("rSquared") > 0.9);
        assertTrue(c.score() >= 90, "61% RAM is still healthy; the trend is informational, got " + c.score());
    }

    @Test
    void aFlatHistoryProducesNoTrendClaim() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.RAM_UTILIZATION, null, 7, FIVE_MIN, t -> 50)
                .recentConstant(MetricType.RAM_UTILIZATION, null, 50);
        assertFalse(hasFinding(analyzer.analyze(ctx(d)), "MEMORY_RISING_TREND"));
    }

    @Test
    void fewShortSessionsScatteredOverDaysAreNotTreatedAsAContinuousHistory() {
        // Regression (found by running against a real, sparse database): three 6-hour sessions on days 6, 3 and 1 give
        // 36 populated buckets over a 5-day span (density ~0.15). A perfect rising line through them must NOT be reported.
        java.time.Instant now = TestSupport.NOW;
        FakeHealthData d = new FakeHealthData();
        for (int daysBack : new int[] { 6, 3, 1 }) {
            java.time.Instant from = now.minus(Duration.ofDays(daysBack));
            d.values(MetricType.RAM_UTILIZATION, null, from, from.plus(Duration.ofHours(6)), FIVE_MIN,
                    t -> 40 + 2 * FakeHealthData.hours(now.minus(Duration.ofDays(6)), t) / 24.0);
        }
        d.recentConstant(MetricType.RAM_UTILIZATION, null, 55);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertFalse(hasFinding(c, "MEMORY_RISING_TREND"), "sparse history must not produce a trend claim");
        assertFalse(c.instances().get(0).metrics().containsKey("trendPerDay"));
        assertTrue(c.instances().get(0).metrics().get("historyDensity") < 0.25);
    }

    @Test
    void tooShortAHistoryIsNotEligibleForATrendEvenIfItLooksSteep() {
        // 6 hours of history < the 24 h minimum span: a steep line must NOT be reported as a trend.
        FakeHealthData d = new FakeHealthData()
                .values(MetricType.RAM_UTILIZATION, null, TestSupport.NOW.minus(java.time.Duration.ofHours(7)),
                        TestSupport.NOW.minus(java.time.Duration.ofHours(1)), FIVE_MIN, t -> 30 + 4 * FakeHealthData.hours(TestSupport.NOW.minus(java.time.Duration.ofHours(7)), t))
                .recentConstant(MetricType.RAM_UTILIZATION, null, 55);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertFalse(hasFinding(c, "MEMORY_RISING_TREND"));
        assertFalse(c.instances().get(0).metrics().containsKey("trendPerDay"));
    }
}
