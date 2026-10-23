package com.nexusrmm.health.analysis;

import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.provider.EventMonitoring;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.finding;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 12: reliability (hardware events) analyzer. */
class ReliabilityAnalyzerTest {

    private final ReliabilityAnalyzer analyzer = new ReliabilityAnalyzer();

    private ComponentHealth run(FakeHealthData d) {
        return analyzer.analyze(ctx(d));
    }

    @Test
    void zeroEventsFromAWorkingMonitorIsHealthy() {
        ComponentHealth c = run(new FakeHealthData());
        assertEquals(100, c.score());
        assertEquals(HealthStatus.HEALTHY, c.status());
    }

    @Test
    void zeroEventsFromAMonitorThatIsNotApplicableIsNoDataNotHealthy() {
        ComponentHealth c = run(new FakeHealthData().monitoring(new EventMonitoring(false, false, NOW,
                "Windows Event Log monitoring only applies on Windows; this host is running Linux.")));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertNull(c.score());
        assertTrue(c.noDataReason().contains("only applies on Windows"), c.noDataReason());
    }

    @Test
    void aFailedLastPollIsNoDataWithTheError() {
        ComponentHealth c = run(new FakeHealthData().monitoring(new EventMonitoring(true, false, NOW, "Timed out querying the Windows System event log.")));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertTrue(c.noDataReason().contains("Timed out"), c.noDataReason());
    }

    @Test
    void oneRecentCriticalWheaEventIsWeightedByLevelAndRecency() {
        // 15 base x 1.5 (Critical) x 1.0 (<=24h) = 22.5 -> score 77.5 -> 78
        ComponentHealth c = run(new FakeHealthData().event(Duration.ofHours(2), "HARDWARE", "Critical"));
        assertEquals(78, c.score());
        assertEquals(HealthStatus.FAIR, c.status());
        assertTrue(hasFinding(c, "EVENTS_HARDWARE"));
    }

    @Test
    void anOldEventCountsForLess() {
        // 15 x 1.0 x 0.4 (older than 72h) = 6 -> 94
        assertEquals(94, run(new FakeHealthData().event(Duration.ofDays(5), "HARDWARE", "Error")).score());
    }

    @Test
    void storageErrorsAccumulateUntilTheirCategoryCap() {
        FakeHealthData five = new FakeHealthData();
        for (int i = 0; i < 5; i++) five.event(Duration.ofHours(1 + i), "STORAGE", "Error");
        assertEquals(50, run(five).score(), "5 x 10 = 50");

        FakeHealthData twenty = new FakeHealthData();
        for (int i = 0; i < 20; i++) twenty.event(Duration.ofHours(1), "STORAGE", "Error");
        assertEquals(40, run(twenty).score(), "capped at 60 for the category, not 200");
    }

    @Test
    void severeMultiCategoryFailureBottomsOutAtZero() {
        FakeHealthData d = new FakeHealthData();
        for (int i = 0; i < 5; i++) d.event(Duration.ofHours(1), "HARDWARE", "Critical");
        for (int i = 0; i < 10; i++) d.event(Duration.ofHours(1), "STORAGE", "Error");
        ComponentHealth c = run(d);
        assertEquals(0, c.score());
        assertEquals(HealthStatus.CRITICAL, c.status());
    }

    @Test
    void eventsOutsideTheWindowAreIgnored() {
        assertEquals(100, run(new FakeHealthData().event(Duration.ofDays(30), "HARDWARE", "Critical")).score());
    }

    @Test
    void metricsFeedTheRiskEngineAndCountRecentEventsSeparately() {
        FakeHealthData d = new FakeHealthData().event(Duration.ofHours(1), "STORAGE", "Error").event(Duration.ofDays(3), "STORAGE", "Error");
        var m = run(d).instances().get(0).metrics();
        assertEquals(2.0, m.get("storageEvents"));
        assertEquals(1.0, m.get("storageLast24h"));
        assertEquals(1.0, m.get("eventsLast24h"));
        assertTrue(finding(run(d), "EVENTS_STORAGE").message().contains("1 in the last 24 h"));
    }
}
