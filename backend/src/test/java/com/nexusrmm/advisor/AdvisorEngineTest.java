package com.nexusrmm.advisor;

import com.nexusrmm.advisor.config.AdvisorSettings;
import com.nexusrmm.advisor.engine.AdvisorEngine;
import com.nexusrmm.advisor.model.Issue;
import com.nexusrmm.advisor.model.Recommendation;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.model.Severity;
import com.nexusrmm.health.service.HealthFacade;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.nexusrmm.advisor.AdvisorTestSupport.FIVE_MIN;
import static com.nexusrmm.advisor.AdvisorTestSupport.GB;
import static com.nexusrmm.advisor.AdvisorTestSupport.analyze;
import static com.nexusrmm.advisor.AdvisorTestSupport.group;
import static com.nexusrmm.advisor.AdvisorTestSupport.machine;
import static com.nexusrmm.advisor.AdvisorTestSupport.series;
import static com.nexusrmm.advisor.AdvisorTestSupport.snapshot;
import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvisorEngineTest {

    private final AdvisorEngine engine = new AdvisorEngine(AdvisorSettings.defaults());

    private List<Issue> run(FakeHealthData d, ProcessSnapshot ps) {
        HealthFacade.Analysis a = analyze(d);
        return engine.evaluate(new AdvisorEngine.Input(a.health(), a.risk(), ps), NOW);
    }

    private static Issue issue(List<Issue> issues, String key) {
        return issues.stream().filter(i -> i.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no issue " + key + " in " + issues.stream().map(Issue::key).toList()));
    }

    private static boolean hasKey(List<Issue> issues, String key) {
        return issues.stream().anyMatch(i -> i.key().equals(key));
    }

    private static boolean hasRec(Issue i, String code) {
        return i.recommendations().stream().anyMatch(r -> r.code().equals(code));
    }

    // ---- baseline behaviour --------------------------------------------------------------------

    @Test
    void aQuietMachineProducesNoIssuesAndNothingIsInvented() {
        List<Issue> issues = run(machine(30, 50, 50, 55), null);
        assertTrue(issues.stream().noneMatch(i -> i.severity() != Severity.INFO),
                "unexpected: " + issues.stream().map(Issue::key).toList());
    }

    @Test
    void aMachineWithNoDataIsStillCollectingNotHealthy() {
        List<Issue> issues = run(new FakeHealthData(), null);
        assertEquals(1, issues.size());
        Issue i = issues.get(0);
        assertEquals("visibility:warmup", i.key());
        assertEquals(Severity.INFO, i.severity());
        assertTrue(i.title().contains("collecting"));
        assertTrue(hasRec(i, "visibility.wait"));
    }

    @Test
    void aMissingSensorIsReportedAsAVisibilityGapNotAsHealthy() {
        FakeHealthData d = new FakeHealthData();
        series(d, MetricType.CPU_UTILIZATION, null, t -> 30);
        series(d, MetricType.RAM_UTILIZATION, null, t -> 50);
        series(d, MetricType.STORAGE_UTILIZATION, "C:\\", t -> 50);
        List<Issue> issues = run(d, null);
        Issue gap = issue(issues, "visibility:gaps");
        assertTrue(gap.summary().contains("not the same as healthy"));
        assertTrue(gap.evidence().containsKey("THERMAL"));
        assertEquals(Severity.INFO, gap.severity());
    }

    // ---- CPU -----------------------------------------------------------------------------------

    @Test
    void sustainedCpuSaturationIsCriticalWithGenericAdviceWhenNoProcessDataExists() {
        Issue i = issue(run(machine(96, 50, 50, 55), null), "cpu:load");
        assertEquals(Severity.CRITICAL, i.severity());
        assertEquals("CPU is overloaded", i.title());
        assertTrue(hasRec(i, "cpu.identify"));
        assertTrue(i.evidence().get("attribution").toString().contains("Process insight is not running"));
        assertFalse(i.evidence().containsKey("topProcess"));
    }

    @Test
    void cpuIssueNamesTheDominantProcessAndLeadsWithItsSpecificAdvice() {
        ProcessSnapshot ps = snapshot(true, 3600L, List.of(
                group("chrome.exe", 40, 62.0, 6 * GB, 37.0), group("MsMpEng.exe", 1, 6.0, GB / 2, 3.0)), List.of());
        Issue i = issue(run(machine(96, 50, 50, 55), ps), "cpu:load");
        assertTrue(i.summary().contains("chrome.exe (40 processes)"));
        assertTrue(i.summary().contains("62.0% of total CPU"));
        assertTrue(i.summary().contains("most of what is in use"));
        assertEquals("chrome.exe", i.evidence().get("topProcess"));
        assertEquals(Boolean.TRUE, i.evidence().get("dominant"));
        assertEquals("process.chrome", i.recommendations().get(0).code());
        assertTrue(hasRec(i, "cpu.identify"));
        assertNotNull(i.evidence().get("attributionNote"));
    }

    @Test
    void anUnknownHeavyProcessGetsALookItUpRecommendation() {
        ProcessSnapshot ps = snapshot(true, 100L, List.of(group("zzqx.exe", 1, 55.0, GB, 6.0)), List.of());
        Issue i = issue(run(machine(96, 50, 50, 55), ps), "cpu:load");
        Recommendation first = i.recommendations().get(0);
        assertEquals("cpu.unknown-process", first.code());
        assertTrue(first.action().contains("zzqx.exe"));
    }

    @Test
    void windowsComponentAdviceCarriesACautionNotToEndIt() {
        ProcessSnapshot ps = snapshot(true, 100L, List.of(group("MsMpEng.exe", 1, 40.0, GB, 3.0)), List.of());
        Issue i = issue(run(machine(96, 50, 50, 55), ps), "cpu:load");
        Recommendation r = i.recommendations().get(0);
        assertEquals("process.msmpeng", r.code());
        assertNotNull(r.caution());
        assertTrue(r.caution().contains("do not end it"));
    }

    @Test
    void aProcessBelowTheBlameThresholdIsNotNamed() {
        ProcessSnapshot ps = snapshot(true, 100L, List.of(group("chrome.exe", 5, 2.0, GB, 3.0)), List.of());
        Issue i = issue(run(machine(96, 50, 50, 55), ps), "cpu:load");
        assertFalse(i.evidence().containsKey("topProcess"));
        assertEquals("No single program stood out enough to name.", i.evidence().get("attribution"));
    }

    @Test
    void withoutACpuBaselineNoProgramIsNamedAndTheIssueSaysWhy() {
        ProcessSnapshot ps = snapshot(false, 100L, List.of(), List.of(group("chrome.exe", 5, null, 2 * GB, 12.0)));
        Issue i = issue(run(machine(96, 50, 50, 55), ps), "cpu:load");
        assertFalse(i.evidence().containsKey("topProcess"));
        assertTrue(i.evidence().containsKey("attribution"));
    }

    // ---- Memory --------------------------------------------------------------------------------

    @Test
    void memoryIssueNamesTheLargestHolderInGigabytes() {
        ProcessSnapshot ps = snapshot(true, 3600L, List.of(),
                List.of(group("chrome.exe", 30, null, 6 * GB, 37.5), group("code.exe", 6, null, GB, 6.0)));
        Issue i = issue(run(machine(30, 95, 50, 55), ps), "memory:load");
        assertEquals(Severity.CRITICAL, i.severity());
        assertTrue(i.summary().contains("chrome.exe (30 processes) holds about 6.0 GB (37.5% of RAM)"));
        assertEquals("process.chrome", i.recommendations().get(0).code());
        assertTrue(hasRec(i, "mem.identify"));
    }

    @Test
    void longUptimeAddsARestartRecommendationOnlyWhenMemoryIsUnderPressure() {
        long twentyDays = 20 * 86_400L;
        ProcessSnapshot longUp = snapshot(true, twentyDays, List.of(), List.of(group("chrome.exe", 3, null, 6 * GB, 37.5)));
        ProcessSnapshot shortUp = snapshot(true, 2 * 86_400L, List.of(), List.of(group("chrome.exe", 3, null, 6 * GB, 37.5)));
        Issue withRestart = issue(run(machine(30, 95, 50, 55), longUp), "memory:load");
        Issue without = issue(run(machine(30, 95, 50, 55), shortUp), "memory:load");
        assertTrue(hasRec(withRestart, "mem.restart-windows"));
        assertTrue(withRestart.recommendations().stream().filter(r -> r.code().equals("mem.restart-windows"))
                .findFirst().orElseThrow().action().contains("20 days"));
        assertFalse(hasRec(without, "mem.restart-windows"));
        // specific process advice still comes first
        assertEquals("process.chrome", withRestart.recommendations().get(0).code());
        assertEquals("mem.restart-windows", withRestart.recommendations().get(1).code());
        // and a healthy-memory machine never gets the restart advice, however long it has been up
        assertFalse(hasKey(run(machine(30, 50, 50, 55), longUp), "memory:load"));
    }

    // ---- Thermal correlation -------------------------------------------------------------------

    @Test
    void heatWithHighCpuIsSaidToBeProbablyLoadDriven() {
        Issue i = issue(run(machine(95, 50, 50, 92), null), "thermal:heat");
        assertEquals(Severity.CRITICAL, i.severity());
        assertTrue(i.summary().contains("most likely load-driven"));
        assertTrue(hasRec(i, "thermal.reduce-load"));
        assertFalse(hasRec(i, "thermal.cooling"));
        assertTrue(i.summary().contains("throttles"));
    }

    @Test
    void heatWithLowCpuPointsAtCoolingNotWorkload() {
        Issue i = issue(run(machine(15, 50, 50, 85), null), "thermal:heat");
        assertEquals(Severity.WARNING, i.severity());
        assertTrue(i.summary().contains("does not explain it"));
        assertTrue(hasRec(i, "thermal.cooling"));
        assertFalse(hasRec(i, "thermal.reduce-load"));
        assertNotNull(i.recommendations().stream().filter(r -> r.code().equals("thermal.cooling")).findFirst().orElseThrow().caution());
    }

    // ---- Storage -------------------------------------------------------------------------------

    @Test
    void aNearlyFullVolumeIsCriticalAndLeadsWithAnUrgentStep() {
        Issue i = issue(run(machine(30, 50, 96, 55), null), "storage:C:\\");
        assertEquals(Severity.CRITICAL, i.severity());
        assertEquals("C:\\ is almost full", i.title());
        assertEquals("storage.urgent", i.recommendations().get(0).code());
        assertTrue(hasRec(i, "storage.cleanup"));
    }

    @Test
    void aFastFillingVolumeReportsTheProjectionAndIsRankedByUrgency() {
        FakeHealthData d = new FakeHealthData();
        series(d, MetricType.CPU_UTILIZATION, null, t -> 30);
        series(d, MetricType.RAM_UTILIZATION, null, t -> 50);
        series(d, MetricType.CPU_TEMPERATURE, null, t -> 55);
        Instant0 start = new Instant0();
        series(d, MetricType.STORAGE_UTILIZATION, "C:\\", t -> 40 + FakeHealthData.hours(start.t(), t) / 24.0 * 5.0);
        Issue i = issue(run(d, null), "storage:C:\\");
        assertEquals(Severity.WARNING, i.severity());
        assertEquals("C:\\ is filling up fast", i.title());
        Double days = (Double) i.evidence().get("daysToFull");
        assertNotNull(days);
        assertTrue(days > 3 && days < 8, "daysToFull=" + days);
        assertEquals("storage.urgent", i.recommendations().get(0).code(), "<= 7 days is urgent");
    }

    /** NOW minus seven days, kept in one place. */
    private static final class Instant0 {
        java.time.Instant t() {
            return NOW.minus(Duration.ofDays(7));
        }
    }

    // ---- Reliability ---------------------------------------------------------------------------

    @Test
    void diskErrorEventsLeadWithBackupAndExplainTheSmartLimitation() {
        FakeHealthData d = machine(30, 50, 50, 55);
        for (int k = 0; k < 6; k++) {
            d.event(Duration.ofHours(1 + k), "STORAGE", "Critical");
        }
        Issue i = issue(run(d, null), "reliability:storage");
        assertTrue(i.severity().ordinal() >= Severity.WARNING.ordinal());
        assertEquals("rel.backup-now", i.recommendations().get(0).code());
        assertTrue(i.recommendations().stream().anyMatch(r -> r.action().contains("does not collect SMART")));
        assertEquals("Disk error events", i.title());
    }

    @Test
    void hardwareAndPowerEventsGetTheirOwnAdvice() {
        FakeHealthData d = machine(30, 50, 50, 55);
        for (int k = 0; k < 4; k++) {
            d.event(Duration.ofHours(2 + k), "HARDWARE", "Critical");
            d.event(Duration.ofHours(3 + k), "POWER", "Critical");
        }
        List<Issue> issues = run(d, null);
        assertTrue(hasRec(issue(issues, "reliability:hardware"), "rel.memory-test"));
        assertTrue(hasRec(issue(issues, "reliability:power"), "rel.power-source"));
    }

    // ---- Cross-cutting guarantees --------------------------------------------------------------

    @Test
    void issuesAreRankedCriticalBeforeWarningBeforeInfo() {
        FakeHealthData d = machine(96, 50, 50, 85); // CPU critical, heat warning
        List<Issue> issues = run(d, null);
        for (int k = 1; k < issues.size(); k++) {
            assertTrue(issues.get(k - 1).priority() >= issues.get(k).priority());
        }
        assertEquals("cpu:load", issues.get(0).key());
        assertTrue(issues.get(0).severity().ordinal() > issues.get(issues.size() - 1).severity().ordinal()
                || issues.size() == 1);
    }

    @Test
    void everyIssueHasAtLeastOneRecommendationAndEvidenceOrSummary() {
        FakeHealthData d = machine(96, 95, 96, 92);
        for (int k = 0; k < 5; k++) {
            d.event(Duration.ofHours(1 + k), "STORAGE", "Critical");
            d.event(Duration.ofHours(1 + k), "HARDWARE", "Critical");
        }
        List<Issue> issues = run(d, null);
        assertTrue(issues.size() >= 5);
        for (Issue i : issues) {
            assertFalse(i.recommendations().isEmpty(), i.key());
            assertFalse(i.summary().isBlank(), i.key());
            assertFalse(i.sourceCodes().isEmpty() && !i.key().startsWith("visibility"), i.key());
        }
    }

    @Test
    void issueKeysAreUniqueWithinAnEvaluation() {
        FakeHealthData d = machine(96, 95, 96, 92);
        List<Issue> issues = run(d, null);
        assertEquals(issues.size(), issues.stream().map(Issue::key).distinct().count());
    }

    @Test
    void aWarningFindingNoRuleKnowsStillSurfacesAsAGenericIssue() {
        Finding unknown = Finding.warning("CPU_FROM_THE_FUTURE", "Something new was detected.", Map.of("x", 1));
        ComponentHealth cpu = new ComponentHealth(ComponentId.CPU, "CPU", 70, HealthStatus.FAIR, 1.0, 100, 1.0, "test",
                List.of(new InstanceHealth(null, 70, HealthStatus.FAIR, 100, 1.0, Map.of(), List.of(unknown), null)),
                List.of(unknown), null);
        HealthReport h = new HealthReport(NOW, "c", 70, HealthStatus.FAIR, 1.0, "test", List.of(cpu), List.of());
        List<Issue> issues = engine.evaluate(new AdvisorEngine.Input(h, null, null), NOW);
        assertEquals(1, issues.size());
        Issue i = issues.get(0);
        assertEquals(Severity.WARNING, i.severity());
        assertTrue(i.sourceCodes().contains("CPU_FROM_THE_FUTURE"));
        assertEquals("generic.review-health", i.recommendations().get(0).code());
        assertNull(i.firstSeen(), "tracking is the tracker's job, not the engine's");
    }

    @Test
    void anInfoFindingNoRuleKnowsIsNotTurnedIntoNoise() {
        Finding info = Finding.info("CPU_SOMETHING_MINOR", "FYI.", Map.of());
        ComponentHealth cpu = new ComponentHealth(ComponentId.CPU, "CPU", 95, HealthStatus.HEALTHY, 1.0, 100, 1.0, "test",
                List.of(), List.of(info), null);
        HealthReport h = new HealthReport(NOW, "c", 95, HealthStatus.HEALTHY, 1.0, "test", List.of(cpu), List.of());
        assertTrue(engine.evaluate(new AdvisorEngine.Input(h, null, null), NOW).isEmpty());
    }

    @Test
    void evaluationWorksWithoutARiskReportAndWithoutProcessData() {
        HealthFacade.Analysis a = analyze(machine(96, 50, 50, 55));
        List<Issue> issues = engine.evaluate(new AdvisorEngine.Input(a.health(), null, null), NOW);
        assertTrue(hasKey(issues, "cpu:load"));
    }

    @Test
    void anUnavailableProcessSnapshotIsHandledLikeNoSnapshotAndItsReasonIsShown() {
        ProcessSnapshot ps = ProcessSnapshot.unavailable(NOW, "sampler disabled by configuration");
        Issue i = issue(run(machine(96, 50, 50, 55), ps), "cpu:load");
        assertEquals("sampler disabled by configuration", i.evidence().get("attribution"));
    }

    @Test
    void risingCpuTrendIsInformationalOnly() {
        FakeHealthData d = new FakeHealthData();
        java.time.Instant start = NOW.minus(Duration.ofDays(7));
        series(d, MetricType.CPU_UTILIZATION, null, t -> Math.min(60, 20 + FakeHealthData.hours(start, t) / 24.0 * 5.0));
        series(d, MetricType.RAM_UTILIZATION, null, t -> 50);
        series(d, MetricType.STORAGE_UTILIZATION, "C:\\", t -> 50);
        series(d, MetricType.CPU_TEMPERATURE, null, t -> 55);
        List<Issue> issues = run(d, null);
        if (hasKey(issues, "cpu:trend")) {
            assertEquals(Severity.INFO, issue(issues, "cpu:trend").severity());
        }
        assertTrue(issues.stream().noneMatch(i -> i.severity() == Severity.CRITICAL));
    }
}
