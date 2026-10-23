package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.stats.Curves;

import java.util.ArrayList;
import java.util.List;

import static com.nexusrmm.health.analysis.AnalyzerSupport.ev;
import static com.nexusrmm.health.analysis.AnalyzerSupport.metrics;

/**
 * Per physical disk: is read/write activity unusually HIGH compared with
 * this machine's own baseline? Throughput has no healthy level of its own,
 * and Module 1 collects no latency, queue-depth or SMART data, so no
 * absolute threshold and no claim about drive health is made. A
 * statistically unusual surge is a weak, low-weight signal (max penalty
 * 15 per direction); unusually LOW activity is not penalized (idle is
 * normal). With too little history for a baseline the disk is NO_DATA.
 */
public final class StorageIoAnalyzer implements ComponentAnalyzer {

    private static final double MAX_PENALTY_PER_DIRECTION = 15;

    @Override
    public ComponentId component() {
        return ComponentId.STORAGE_IO;
    }

    @Override
    public ComponentHealth analyze(AnalysisContext ctx) {
        String basis = "Read/write throughput per disk compared with this machine's own " + ctx.settings().windows().longWindow().toDays()
                + "-day baseline (robust z-score). No absolute threshold; latency, queue depth and SMART data are not collected.";
        List<InstanceHealth> disks = new ArrayList<>();
        for (String label : AnalyzerSupport.instancesOrSingle(ctx, MetricType.STORAGE_READ_BYTES_PER_SEC, MetricType.STORAGE_WRITE_BYTES_PER_SEC)) {
            disks.add(analyzeDisk(ctx, label));
        }
        return AnalyzerSupport.assemble(ctx, ComponentId.STORAGE_IO, basis, disks, AnalyzerSupport.Aggregation.MIN,
                List.of(), "Storage I/O could not be assessed.");
    }

    private InstanceHealth analyzeDisk(AnalysisContext ctx, String label) {
        var read = AnalyzerSupport.recent(ctx, MetricType.STORAGE_READ_BYTES_PER_SEC, label);
        var write = AnalyzerSupport.recent(ctx, MetricType.STORAGE_WRITE_BYTES_PER_SEC, label);
        int min = ctx.settings().minSamples();

        boolean readOk = read.valueCount() >= min;
        boolean writeOk = write.valueCount() >= min;
        if (!readOk && !writeOk) {
            String reason = AnalyzerSupport.insufficiencyReason(ctx, MetricType.STORAGE_READ_BYTES_PER_SEC, label, read,
                    "disk read throughput", min);
            return InstanceHealth.noData(label, reason, Math.max(read.valueCount(), write.valueCount()),
                    Math.max(read.coverage(), write.coverage()));
        }

        var readScan = readOk ? AnomalySupport.scan(ctx, MetricType.STORAGE_READ_BYTES_PER_SEC, label, read.values()) : null;
        var writeScan = writeOk ? AnomalySupport.scan(ctx, MetricType.STORAGE_WRITE_BYTES_PER_SEC, label, write.values()) : null;
        boolean anyAssessed = (readScan != null && readScan.assessed()) || (writeScan != null && writeScan.assessed());

        if (!anyAssessed) {
            var s = readScan != null ? readScan : writeScan;
            String why = s.state() == AnomalySupport.State.NO_BASELINE
                    ? "Insufficient history for an activity baseline: " + s.baselineBuckets() + " of " + s.requiredBuckets()
                        + " required " + ctx.settings().windows().bucket().toMinutes() + "-minute buckets."
                    : "Baseline has no variation (e.g. always idle), so no anomaly can be judged.";
            return InstanceHealth.noData(label, why, Math.max(read.valueCount(), write.valueCount()),
                    Math.max(read.coverage(), write.coverage()));
        }

        double penalty = 0;
        List<Finding> findings = new ArrayList<>();
        penalty += direction("read", label, readScan, ctx, findings);
        penalty += direction("write", label, writeScan, ctx, findings);
        int score = Curves.score(penalty);

        return new InstanceHealth(label, score, Scoring.statusFor(score, ctx.settings()),
                read.valueCount() + write.valueCount(), AnalyzerSupport.round(Math.max(read.coverage(), write.coverage()), 3),
                metrics("readMeanBytesPerSec", readScan == null ? null : readScan.recentMean(),
                        "writeMeanBytesPerSec", writeScan == null ? null : writeScan.recentMean(),
                        "readZ", readScan != null && readScan.assessed() ? readScan.z() : null,
                        "writeZ", writeScan != null && writeScan.assessed() ? writeScan.z() : null),
                findings, null);
    }

    private double direction(String name, String label, AnomalySupport.Result r, AnalysisContext ctx, List<Finding> findings) {
        if (r == null || r.state() != AnomalySupport.State.HIGH) {
            return 0;
        }
        double z = r.z();
        double zt = ctx.settings().anomaly().zThreshold();
        findings.add(Finding.warning("STORAGE_IO_" + name.toUpperCase() + "_SURGE", label + " " + name + " throughput averages "
                + AnomalySupport.rate(r.recentMean()) + " versus a typical " + AnomalySupport.rate(r.baselineMedian())
                + " (robust z = " + AnalyzerSupport.round(z, 1) + ").",
                ev("instance", label, "recentMean", r.recentMean(), "baselineMedian", r.baselineMedian(), "z", z)));
        return Curves.ramp(z, zt, zt * 3, MAX_PENALTY_PER_DIRECTION);
    }
}
