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
import static com.nexusrmm.health.analysis.TestSupport.WEEK_AGO;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 10: battery usage analyzer. */
class BatteryAnalyzerTest {

    private static final String BAT = "System Battery";
    private final BatteryAnalyzer analyzer = new BatteryAnalyzer();

    @Test
    void aHealthyUsagePatternIsHealthy() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.BATTERY_LEVEL, BAT, 7, FIVE_MIN, t -> 80)
                .recentConstant(MetricType.BATTERY_LEVEL, BAT, 80);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(100, c.score());
        assertTrue(c.basis().contains("wear is not assessed"), "the score must state what it cannot know");
    }

    @Test
    void frequentDeepDischargeIsPenalizedAtTheRampMaximum() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.BATTERY_LEVEL, BAT, 7, FIVE_MIN, t -> ((long) FakeHealthData.hours(WEEK_AGO, t)) % 10 < 6 ? 10 : 80)
                .recentConstant(MetricType.BATTERY_LEVEL, BAT, 80);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(60, c.score(), "~60% dwell is past the 50% ramp end -> penalty 40");
        assertTrue(hasFinding(c, "BATTERY_FREQUENT_DEEP_DISCHARGE"));
    }

    @Test
    void criticallyLowAndDischargingIsFlagged() {
        FakeHealthData d = new FakeHealthData().values(MetricType.BATTERY_LEVEL, BAT, NOW.minus(Duration.ofHours(1)), NOW,
                FakeHealthData.STEP, t -> 20 - 15 * FakeHealthData.hours(NOW.minus(Duration.ofHours(1)), t));
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertTrue(hasFinding(c, "BATTERY_CRITICALLY_LOW"));
        assertEquals(85, c.score());
    }

    @Test
    void theSameLowLevelWhileChargingIsNotPenalized() {
        FakeHealthData d = new FakeHealthData().values(MetricType.BATTERY_LEVEL, BAT, NOW.minus(Duration.ofHours(1)), NOW,
                FakeHealthData.STEP, t -> 5 + 15 * FakeHealthData.hours(NOW.minus(Duration.ofHours(1)), t)); // 5% -> ~20%, rising
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(100, c.score());
        assertTrue(c.findings().isEmpty());
    }

    @Test
    void aDesktopWithNoBatteryIsNotApplicableNeverAPerfectScore() {
        FakeHealthData d = new FakeHealthData().valueless(MetricType.BATTERY_LEVEL, null, NOW.minus(Duration.ofHours(1)), NOW,
                FakeHealthData.STEP, AvailabilityStatus.NOT_APPLICABLE, "No battery detected");
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertNull(c.score());
        assertTrue(c.noDataReason().startsWith("Not applicable"), c.noDataReason());
        assertTrue(c.noDataReason().contains("No battery detected"));
    }
}
