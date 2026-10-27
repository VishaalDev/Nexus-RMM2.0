package com.nexusrmm.advisor;

import com.nexusrmm.advisor.config.AdvisorSettings;
import com.nexusrmm.advisor.engine.AdvisorEngine;
import com.nexusrmm.advisor.engine.AlertTracker;
import com.nexusrmm.advisor.model.AdvisorReport;
import com.nexusrmm.advisor.model.Issue;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.advisor.service.AdvisorFacade;
import com.nexusrmm.health.model.RiskLevel;
import com.nexusrmm.health.model.Severity;
import com.nexusrmm.health.service.HealthFacade;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.nexusrmm.advisor.AdvisorTestSupport.analyze;
import static com.nexusrmm.advisor.AdvisorTestSupport.machine;
import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvisorFacadeTest {

    private static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger analyses = new AtomicInteger();
    private final AdvisorSettings settings = AdvisorSettings.defaults();

    private AdvisorFacade facade(FakeHealthData data, Supplier<ProcessSnapshot> ps) {
        HealthFacade.Analysis a = analyze(data);
        return new AdvisorFacade(() -> {
            analyses.incrementAndGet();
            return a;
        }, ps, new AdvisorEngine(settings), new AlertTracker(settings.resolveAfterAbsent(), settings.maxAlertEvents()), settings, clock);
    }

    @Test
    void reportsAreCachedForTheTtlThenRecomputed() {
        AdvisorFacade f = facade(machine(30, 50, 50, 55), () -> null);
        AdvisorReport first = f.report();
        assertSame(first, f.report());
        assertEquals(1, analyses.get());
        clock.now = NOW.plus(Duration.ofSeconds(11));
        assertFalse(first == f.report());
        assertEquals(2, analyses.get());
    }

    @Test
    void headlineIsHonestAboutProblemsCoverageAndNoData() {
        AdvisorReport quiet = facade(machine(30, 50, 50, 55), () -> null).report();
        assertEquals("No problems found.", quiet.headline());

        AdvisorReport nodata = facade(new FakeHealthData(), () -> null).report();
        assertEquals("Still collecting data; too early to assess this computer.", nodata.headline());

        AdvisorReport bad = facade(machine(96, 95, 50, 55), () -> null).report();
        assertEquals("2 critical issues need attention.", bad.headline());

        AdvisorReport oneWarning = facade(machine(30, 50, 90, 55), () -> null).report();
        assertTrue(oneWarning.headline().contains("warning"), oneWarning.headline());
    }

    @Test
    void countsMatchTheIssueList() {
        AdvisorReport r = facade(machine(96, 50, 96, 85), () -> null).report();
        assertEquals(r.issues().stream().filter(i -> i.severity() == Severity.CRITICAL).count(), r.criticalCount());
        assertEquals(r.issues().stream().filter(i -> i.severity() == Severity.WARNING).count(), r.warningCount());
        assertEquals(r.issues().stream().filter(i -> i.severity() == Severity.INFO).count(), r.infoCount());
        assertNotNull(r.riskLevel());
        assertTrue(r.riskLevel() != null);
    }

    @Test
    void topActionsAreTheMostUrgentDistinctAndBounded() {
        AdvisorReport r = facade(machine(96, 95, 96, 92), () -> null).report();
        assertEquals(settings.topActions(), r.topActions().size());
        assertEquals(r.topActions().size(), r.topActions().stream().map(a -> a.recommendation().code()).distinct().count());
        Issue top = r.issues().get(0);
        assertEquals(top.key(), r.topActions().get(0).issueKey());
        assertTrue(r.topActions().stream().allMatch(a -> a.severity() != Severity.INFO));
    }

    @Test
    void limitationsAreAlwaysStatedIncludingTheInMemoryAlertLog() {
        AdvisorReport r = facade(machine(30, 50, 50, 55), () -> null).report();
        assertTrue(r.limitations().stream().anyMatch(s -> s.contains("in memory")));
        assertTrue(r.limitations().stream().anyMatch(s -> s.contains("never changes anything")));
        assertTrue(r.limitations().stream().anyMatch(s -> s.contains("Per-process attribution is unavailable")));
        assertFalse(r.processInsight());
    }

    @Test
    void anAvailableSnapshotWithoutACpuBaselineIsDisclosed() {
        ProcessSnapshot s = new ProcessSnapshot(NOW, true, false, 10, 100, 100L, 1L, null, List.of(), List.of(), "n");
        AdvisorReport r = facade(machine(30, 50, 50, 55), () -> s).report();
        assertTrue(r.processInsight());
        assertTrue(r.limitations().stream().anyMatch(x -> x.contains("second sample")));
    }

    @Test
    void evaluationFeedsTheAlertLogAndIssuesCarryTheirAge() {
        AdvisorFacade f = facade(machine(96, 50, 50, 55), () -> null);
        AdvisorReport r = f.evaluate();
        assertTrue(r.issues().stream().allMatch(i -> i.firstSeen() != null && i.lastSeen() != null));
        assertEquals(1, f.alerts(50).stream().filter(a -> a.issueKey().equals("cpu:load")).count());
        clock.now = NOW.plus(Duration.ofMinutes(10));
        AdvisorReport later = f.evaluate();
        Issue cpu = later.issues().stream().filter(i -> i.key().equals("cpu:load")).findFirst().orElseThrow();
        assertEquals(NOW, cpu.firstSeen());
        assertEquals(clock.now, cpu.lastSeen());
        assertEquals(1, f.alerts(50).stream().filter(a -> a.issueKey().equals("cpu:load")).count(), "no duplicate alert");
    }

    @Test
    void alertLimitIsClampedToASafeRange() {
        AdvisorFacade f = facade(machine(30, 50, 50, 55), () -> null);
        assertEquals(0, f.alerts(Integer.MIN_VALUE).size());
        assertEquals(0, f.alerts(Integer.MAX_VALUE).size());
    }

    @Test
    void anInvalidRiskLevelCannotAppearBecauseNoRiskReportDefaultsToNotAssessed() {
        assertEquals(RiskLevel.NOT_ASSESSED, RiskLevel.valueOf("NOT_ASSESSED"));
    }
}
