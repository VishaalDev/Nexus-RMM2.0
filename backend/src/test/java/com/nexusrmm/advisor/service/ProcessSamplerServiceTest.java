package com.nexusrmm.advisor.service;

import com.nexusrmm.advisor.config.AdvisorProperties;
import com.nexusrmm.advisor.config.AdvisorSettings;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.advisor.process.ProcessSource;
import com.nexusrmm.advisor.process.RawProcess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSamplerServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final AdvisorProperties props = new AdvisorProperties();
    private boolean fail;
    private boolean baseline;

    private final ProcessSource source = () -> {
        if (fail) {
            throw new IllegalStateException("boom");
        }
        return new ProcessSource.Capture(List.of(new RawProcess(5, "a.exe", 0.5, 1024, 3)), 4, 1L << 30, 100, baseline);
    };

    private ProcessSamplerService service() {
        ProcessSamplerService s = new ProcessSamplerService(source, props.toSettings(), props);
        s.useClock(now::get);
        return s;
    }

    @Test
    void beforeTheFirstSampleTheSnapshotIsExplicitlyUnavailableNotEmpty() {
        ProcessSnapshot s = service().latest();
        assertFalse(s.available());
        assertTrue(s.note().contains("No process sample has been taken yet"));
    }

    @Test
    void aSampleBecomesTheLatestSnapshot() {
        ProcessSamplerService svc = service();
        baseline = true;
        svc.sample();
        ProcessSnapshot s = svc.latest();
        assertTrue(s.available());
        assertTrue(s.cpuAvailable());
        assertEquals("a.exe", s.topByMemory().get(0).name());
    }

    @Test
    void aFailingSourceYieldsAnUnavailableSnapshotWithoutThrowing() {
        ProcessSamplerService svc = service();
        fail = true;
        svc.sample();
        ProcessSnapshot s = svc.latest();
        assertFalse(s.available());
        assertTrue(s.note().contains("IllegalStateException"));
        assertFalse(s.note().contains("boom"), "internal messages are not leaked to the API");
    }

    @Test
    void aStaleSnapshotIsNeverPresentedAsCurrent() {
        ProcessSamplerService svc = service();
        baseline = true;
        svc.sample();
        now.set(T0.plus(Duration.ofSeconds(props.getProcessIntervalSeconds() * 4L - 1)));
        assertTrue(svc.latest().available());
        now.set(T0.plus(Duration.ofSeconds(props.getProcessIntervalSeconds() * 4L + 5)));
        ProcessSnapshot stale = svc.latest();
        assertFalse(stale.available());
        assertTrue(stale.note().contains("not shown as current"));
    }

    @Test
    void disablingSamplingStopsCollectionAndSaysWhy() {
        props.setProcessSamplingEnabled(false);
        ProcessSamplerService svc = service();
        svc.sample();
        ProcessSnapshot s = svc.latest();
        assertFalse(s.available());
        assertTrue(s.note().contains("disabled"));
    }

    @Test
    void schedulingValuesAreValidatedBecauseScheduledReadsThemUnchecked() {
        AdvisorProperties bad = new AdvisorProperties();
        bad.setProcessIntervalSeconds(0);
        assertThrows(IllegalArgumentException.class, bad::toSettings);
        AdvisorProperties bad2 = new AdvisorProperties();
        bad2.setEvaluationIntervalSeconds(1);
        assertThrows(IllegalArgumentException.class, bad2::toSettings);
        assertEquals(AdvisorSettings.defaults().processTopN(), new AdvisorProperties().toSettings().processTopN());
    }
}
