package com.nexusrmm.advisor;

import com.nexusrmm.advisor.process.ProcessGroup;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.analysis.HealthAnalysisEngine;
import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.RiskReport;
import com.nexusrmm.health.risk.RiskEngine;
import com.nexusrmm.health.service.HealthFacade;
import com.nexusrmm.health.support.FakeHealthData;

import java.time.Duration;
import java.util.List;
import java.util.function.ToDoubleFunction;

import static com.nexusrmm.health.support.FakeHealthData.NOW;

/** Builds synthetic machines and runs the real Module 2 engines over them. */
public final class AdvisorTestSupport {

    public static final Duration FIVE_MIN = Duration.ofMinutes(5);
    private static final HealthSettings SETTINGS = HealthSettings.defaults();
    private static final HealthAnalysisEngine HEALTH = new HealthAnalysisEngine(HealthAnalysisEngine.defaultAnalyzers(), SETTINGS);
    private static final RiskEngine RISK = new RiskEngine(SETTINGS);

    private AdvisorTestSupport() {
    }

    /** A week of 5-minute history plus a dense last hour, both from the same function. */
    public static FakeHealthData series(FakeHealthData d, MetricType type, String instance, ToDoubleFunction<java.time.Instant> f) {
        return d.history(type, instance, 7, FIVE_MIN, f)
                .values(type, instance, NOW.minus(Duration.ofHours(1)), NOW, FakeHealthData.STEP, f);
    }

    public static FakeHealthData machine(double cpu, double ram, double disk, double temp) {
        FakeHealthData d = new FakeHealthData();
        series(d, MetricType.CPU_UTILIZATION, null, t -> cpu);
        series(d, MetricType.RAM_UTILIZATION, null, t -> ram);
        series(d, MetricType.STORAGE_UTILIZATION, "C:\\", t -> disk);
        series(d, MetricType.CPU_TEMPERATURE, null, t -> temp);
        return d;
    }

    public static HealthFacade.Analysis analyze(FakeHealthData d) {
        HealthReport h = HEALTH.analyze(d, NOW);
        RiskReport r = RISK.assess(h, NOW);
        return new HealthFacade.Analysis(h, r);
    }

    public static ProcessGroup group(String name, int count, Double cpu, long bytes, Double memPct) {
        var k = com.nexusrmm.advisor.process.ProcessKnowledge.lookup(name);
        return new ProcessGroup(name, count, cpu, bytes, memPct, count * 10,
                k.map(com.nexusrmm.advisor.process.ProcessKnowledge.Entry::description).orElse(null),
                k.map(com.nexusrmm.advisor.process.ProcessKnowledge.Entry::advice).orElse(null),
                k.map(com.nexusrmm.advisor.process.ProcessKnowledge.Entry::systemComponent).orElse(false));
    }

    public static ProcessSnapshot snapshot(boolean cpuAvailable, Long uptimeSeconds, List<ProcessGroup> byCpu, List<ProcessGroup> byMem) {
        double total = byCpu.stream().mapToDouble(g -> g.cpuPercent() == null ? 0 : g.cpuPercent()).sum();
        return new ProcessSnapshot(NOW, true, cpuAvailable, 200, 3000, uptimeSeconds, 16L * 1_073_741_824L,
                cpuAvailable ? total : null, byCpu, byMem, "test");
    }

    public static final long GB = 1_073_741_824L;
}
