package com.nexusrmm.advisor;

import com.nexusrmm.advisor.process.ProcessAggregator;
import com.nexusrmm.advisor.process.ProcessGroup;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.advisor.process.ProcessSource;
import com.nexusrmm.advisor.process.RawProcess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.nexusrmm.advisor.AdvisorTestSupport.GB;
import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessAggregatorTest {

    private static ProcessSource.Capture capture(boolean baseline, int cpus, RawProcess... ps) {
        return new ProcessSource.Capture(List.of(ps), cpus, 16 * GB, 3 * 86_400L, baseline);
    }

    private static RawProcess p(int pid, String name, Double cores, long mb) {
        return new RawProcess(pid, name, cores, mb * 1024 * 1024, 10);
    }

    @Test
    void helperProcessesOfOneApplicationBecomeOneGroup() {
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(true, 8,
                p(1, "chrome.exe", 0.5, 300), p(2, "chrome.exe", 0.5, 200), p(3, "Chrome.EXE", 0.0, 100),
                p(4, "notepad.exe", 0.0, 20)), 5, NOW);
        ProcessGroup chrome = s.topByMemory().get(0);
        assertEquals("chrome.exe", chrome.name());
        assertEquals(3, chrome.processCount());
        assertEquals(600L * 1024 * 1024, chrome.memoryBytes());
        assertEquals(4, s.processCount());
    }

    @Test
    void cpuIsNormalisedToTheWholeMachine() {
        // 2 busy cores out of 8 logical processors = 25% of the machine.
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(true, 8, p(1, "render.exe", 2.0, 100)), 5, NOW);
        assertEquals(25.0, s.topByCpu().get(0).cpuPercent(), 0.01);
        assertEquals(25.0, s.totalCpuPercent(), 0.01);
    }

    @Test
    void cpuOverOneHundredPercentIsClampedNotReported() {
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(true, 2, p(1, "x.exe", 5.0, 10)), 5, NOW);
        assertEquals(100.0, s.topByCpu().get(0).cpuPercent(), 0.01);
    }

    @Test
    void withoutABaselineCpuIsReportedUnavailableNeverZero() {
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(false, 8, p(1, "a.exe", 3.0, 50)), 5, NOW);
        assertTrue(s.available());
        assertFalse(s.cpuAvailable());
        assertNull(s.totalCpuPercent());
        assertTrue(s.topByCpu().isEmpty());
        assertNull(s.topByMemory().get(0).cpuPercent());
        assertTrue(s.note().contains("Baseline"));
        assertEquals(1, s.topByMemory().size());
    }

    @Test
    void idleProcessIsNotAConsumer() {
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(true, 4,
                p(0, "System Idle Process", 3.5, 0), p(4, "real.exe", 0.4, 10)), 5, NOW);
        assertEquals(1, s.topByCpu().size());
        assertEquals("real.exe", s.topByCpu().get(0).name());
    }

    @Test
    void rankingsAreLimitedToTopNAndSortedDescending() {
        List<RawProcess> list = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            list.add(p(i, "app" + i + ".exe", i / 10.0, i));
        }
        ProcessSnapshot s = ProcessAggregator.aggregate(
                new ProcessSource.Capture(list, 8, 16 * GB, 100, true), 3, NOW);
        assertEquals(3, s.topByCpu().size());
        assertEquals("app20.exe", s.topByCpu().get(0).name());
        assertEquals("app19.exe", s.topByCpu().get(1).name());
        assertEquals("app20.exe", s.topByMemory().get(0).name());
        assertEquals(20, s.processCount());
    }

    @Test
    void wellKnownProcessesAreAnnotatedAndUnknownOnesAreNot() {
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(true, 8,
                p(1, "MsMpEng.exe", 1.0, 200), p(2, "mystery.exe", 0.1, 100)), 5, NOW);
        ProcessGroup defender = s.topByCpu().get(0);
        assertNotNull(defender.description());
        assertTrue(defender.systemComponent());
        ProcessGroup unknown = s.topByCpu().get(1);
        assertNull(unknown.description());
        assertNull(unknown.advice());
        assertFalse(unknown.systemComponent());
    }

    @Test
    void memoryPercentIsShareOfPhysicalRam() {
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(true, 8, p(1, "big.exe", 0.0, 4096)), 5, NOW);
        assertEquals(25.0, s.topByMemory().get(0).memoryPercent(), 0.01);
    }

    @Test
    void anEmptyOrMissingListIsUnavailableWithAReasonNotAnIdleMachine() {
        ProcessSnapshot a = ProcessAggregator.aggregate(null, 5, NOW);
        ProcessSnapshot b = ProcessAggregator.aggregate(new ProcessSource.Capture(List.of(), 4, GB, 1, true), 5, NOW);
        for (ProcessSnapshot s : List.of(a, b)) {
            assertFalse(s.available());
            assertNotNull(s.note());
            assertTrue(s.topByCpu().isEmpty());
        }
    }

    @Test
    void blankNamesAndNegativeValuesAreSkippedOrFlooredNotCrashing() {
        ProcessSnapshot s = ProcessAggregator.aggregate(capture(true, 4,
                new RawProcess(1, " ", 1.0, 5, 1), new RawProcess(2, null, 1.0, 5, 1),
                new RawProcess(3, "ok.exe", -1.0, -50, -3)), 5, NOW);
        assertEquals(1, s.processCount());
        assertEquals(0L, s.topByMemory().get(0).memoryBytes());
        assertEquals(0, s.threadCount());
    }
}
