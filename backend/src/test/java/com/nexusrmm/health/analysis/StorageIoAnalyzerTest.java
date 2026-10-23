package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.analysis.TestSupport.FIVE_MIN;
import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static com.nexusrmm.health.analysis.TestSupport.WEEK_AGO;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 8: storage I/O anomaly analyzer. */
class StorageIoAnalyzerTest {

    private static final String DISK = "\\\\.\\PHYSICALDRIVE0";
    private static final MetricType READ = MetricType.STORAGE_READ_BYTES_PER_SEC;
    private static final MetricType WRITE = MetricType.STORAGE_WRITE_BYTES_PER_SEC;
    private final StorageIoAnalyzer analyzer = new StorageIoAnalyzer();

    private static double wobble(java.time.Instant t) {
        return 2_000_000 + 300_000 * Math.sin(FakeHealthData.hours(WEEK_AGO, t) * 0.9);
    }

    private FakeHealthData baseline() {
        return new FakeHealthData().history(READ, DISK, 7, FIVE_MIN, StorageIoAnalyzerTest::wobble)
                .history(WRITE, DISK, 7, FIVE_MIN, StorageIoAnalyzerTest::wobble);
    }

    @Test
    void usualActivityScoresFullMarks() {
        ComponentHealth c = analyzer.analyze(ctx(baseline().recentConstant(READ, DISK, 2_050_000).recentConstant(WRITE, DISK, 1_950_000)));
        assertEquals(100, c.score());
        assertTrue(c.findings().isEmpty());
    }

    @Test
    void aReadSurgeCostsAtMostFifteenPoints() {
        ComponentHealth c = analyzer.analyze(ctx(baseline().recentConstant(READ, DISK, 400_000_000).recentConstant(WRITE, DISK, 2_000_000)));
        assertEquals(85, c.score());
        assertTrue(hasFinding(c, "STORAGE_IO_READ_SURGE"));
        assertFalse(hasFinding(c, "STORAGE_IO_WRITE_SURGE"));
    }

    @Test
    void surgesInBothDirectionsCostThirty() {
        ComponentHealth c = analyzer.analyze(ctx(baseline().recentConstant(READ, DISK, 400_000_000).recentConstant(WRITE, DISK, 400_000_000)));
        assertEquals(70, c.score());
        assertEquals(HealthStatus.FAIR, c.status());
    }

    @Test
    void idleDisksAreNeverPenalizedForBeingQuiet() {
        ComponentHealth c = analyzer.analyze(ctx(baseline().recentConstant(READ, DISK, 0).recentConstant(WRITE, DISK, 0)));
        assertEquals(100, c.score());
    }

    @Test
    void withoutEnoughHistoryTheDiskIsNoDataAndSaysHowMuchIsMissing() {
        FakeHealthData d = new FakeHealthData()
                .values(READ, DISK, NOW.minus(Duration.ofHours(4)), NOW.minus(Duration.ofHours(1)), FIVE_MIN, StorageIoAnalyzerTest::wobble)
                .recentConstant(READ, DISK, 400_000_000);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertTrue(c.noDataReason().contains("Insufficient history for an activity baseline"), c.noDataReason());
        assertTrue(c.basis().contains("No absolute threshold"), "basis must say no threshold is applied");
    }

    @Test
    void anAlwaysIdleBaselineCannotJudgeAnomalies() {
        FakeHealthData d = new FakeHealthData().history(READ, DISK, 7, FIVE_MIN, t -> 0).recentConstant(READ, DISK, 5_000_000);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertTrue(c.noDataReason().contains("no variation"), c.noDataReason());
    }
}
