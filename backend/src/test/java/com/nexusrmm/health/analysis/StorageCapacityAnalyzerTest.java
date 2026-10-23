package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.analysis.TestSupport.FIVE_MIN;
import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.finding;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static com.nexusrmm.health.analysis.TestSupport.instance;
import static com.nexusrmm.health.analysis.TestSupport.linearPerDay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 7: per-volume capacity (labels deliberately contain backslashes, as real Windows labels do). */
class StorageCapacityAnalyzerTest {

    private static final String C = "C:\\";
    private static final String D = "D:\\";
    private final StorageCapacityAnalyzer analyzer = new StorageCapacityAnalyzer();

    private FakeHealthData volume(FakeHealthData d, String label, double util) {
        return d.recentConstant(MetricType.STORAGE_UTILIZATION, label, util);
    }

    @Test
    void roomyVolumeIsHealthy() {
        ComponentHealth c = analyzer.analyze(ctx(volume(new FakeHealthData(), C, 50)));
        assertEquals(100, c.score());
        assertEquals(HealthStatus.HEALTHY, c.status());
    }

    @Test
    void highUtilizationFollowsTheCurve() {
        // curve(90): 25 + (5/10)*35 = 42.5 -> 57.5 -> 58
        ComponentHealth c = analyzer.analyze(ctx(volume(new FakeHealthData(), C, 90)));
        assertEquals(58, c.score());
        assertEquals(HealthStatus.DEGRADED, c.status());
        assertTrue(hasFinding(c, "STORAGE_HIGH_UTILIZATION"));
        assertEquals(C, finding(c, "STORAGE_HIGH_UTILIZATION").evidence().get("instance"));
    }

    @Test
    void nearlyFullVolumeIsCritical() {
        // curve(97): 60 + (2/5)*40 = 76 -> 24
        ComponentHealth c = analyzer.analyze(ctx(volume(new FakeHealthData(), C, 97)));
        assertEquals(24, c.score());
        assertEquals(HealthStatus.CRITICAL, c.status());
        assertTrue(hasFinding(c, "STORAGE_NEARLY_FULL"));
    }

    @Test
    void worstVolumeIsBlendedWithTheMeanSoItRegistersWithoutErasingTheRest() {
        FakeHealthData d = volume(volume(new FakeHealthData(), C, 50), D, 97);
        ComponentHealth c = analyzer.analyze(ctx(d));
        // min 24, mean 62 -> 0.5*24 + 0.5*62 = 43
        assertEquals(43, c.score());
        assertEquals(100, instance(c, C).score());
        assertEquals(24, instance(c, D).score());
    }

    @Test
    void aFastFillRateProjectsDaysToFull() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.STORAGE_UTILIZATION, C, 7, FIVE_MIN, linearPerDay(40, 5))
                .recentConstant(MetricType.STORAGE_UTILIZATION, C, 75);
        ComponentHealth c = analyzer.analyze(ctx(d));
        var f = finding(c, "STORAGE_FILLING_FAST");
        double days = (Double) f.evidence().get("daysToFull");
        assertEquals(5.0, days, 0.5, "25 points of headroom at 5 points/day");
        assertNotNull(instance(c, C).metrics().get("daysToFull"));
    }

    @Test
    void aSlowFillRateIsInformationalOnly() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.STORAGE_UTILIZATION, C, 7, FIVE_MIN, linearPerDay(40, 2))
                .recentConstant(MetricType.STORAGE_UTILIZATION, C, 54);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertTrue(hasFinding(c, "STORAGE_FILLING"));      // (100-54)/2 = 23 days: inside the 30-day warn horizon, outside the 7-day critical one
        assertFalse(hasFinding(c, "STORAGE_FILLING_FAST"));
    }

    @Test
    void aFlatVolumeHasNoProjection() {
        FakeHealthData d = new FakeHealthData()
                .history(MetricType.STORAGE_UTILIZATION, C, 7, FIVE_MIN, t -> 60)
                .recentConstant(MetricType.STORAGE_UTILIZATION, C, 60);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertFalse(instance(c, C).metrics().containsKey("daysToFull"));
    }

    @Test
    void aVolumeThatDisappearedIsNoDataAndDoesNotDragTheComponent() {
        FakeHealthData d = volume(new FakeHealthData(), C, 50)
                .values(MetricType.STORAGE_UTILIZATION, "E:\\", NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)), FIVE_MIN, t -> 99);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(100, c.score(), "an unplugged 99%-full drive must not be scored as if it were present now");
        assertEquals(HealthStatus.NO_DATA, instance(c, "E:\\").status());
        assertTrue(instance(c, "E:\\").noDataReason().contains("No storage utilization samples in the last 60 min"));
    }

    @Test
    void volumesWhoseDataIsOlderThanTheLongWindowAreNotReportedAsNeverStored() {
        // Regression (found against a real database): all instances fell outside the 7-day window, the analyzer fell back to
        // the un-labelled series, and wrongly claimed nothing had ever been stored.
        FakeHealthData d = new FakeHealthData().values(MetricType.STORAGE_UTILIZATION, C, NOW.minus(Duration.ofDays(20)),
                NOW.minus(Duration.ofDays(19)), FIVE_MIN, t -> 50);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertFalse(c.noDataReason().contains("stored yet."), c.noDataReason());
        assertTrue(c.noDataReason().contains("in the last 7 days"), c.noDataReason());
    }

    @Test
    void noVolumesAtAllIsNoData() {
        ComponentHealth c = analyzer.analyze(ctx(new FakeHealthData()));
        assertEquals(HealthStatus.NO_DATA, c.status());
    }
}
