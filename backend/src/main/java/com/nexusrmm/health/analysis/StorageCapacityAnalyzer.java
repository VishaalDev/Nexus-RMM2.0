package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.stats.Curves;
import com.nexusrmm.health.stats.Stats;

import java.util.ArrayList;
import java.util.List;

import static com.nexusrmm.health.analysis.AnalyzerSupport.ev;
import static com.nexusrmm.health.analysis.AnalyzerSupport.metrics;

/**
 * Per-volume capacity. Penalty = curve(current utilization):
 * (warn-15 -> 0), (warn -> 25), (critical -> 60), (100 -> 100). Capacity
 * moves slowly, so only a handful of recent samples are required and the
 * most recent one is the "current" value.
 *
 * The component score is 0.5 x worst volume + 0.5 x mean of volumes: a
 * full volume must register, but one full archive disk must not by itself
 * be indistinguishable from a full system disk (the sampler does not know
 * which volume is the system volume). Fill-rate (linear trend over the long
 * window) is reported as a finding with a projected days-to-full - only
 * when the trend clears the configured span / bucket-count / R-squared
 * bars - and feeds the risk engine; it does not change the present score.
 */
public final class StorageCapacityAnalyzer implements ComponentAnalyzer {

    private static final int MIN_CAPACITY_SAMPLES = 3;

    @Override
    public ComponentId component() {
        return ComponentId.STORAGE_CAPACITY;
    }

    @Override
    public ComponentHealth analyze(AnalysisContext ctx) {
        String basis = "Current utilization of each volume (latest of the last "
                + AnalyzerSupport.minutes(ctx.settings().windows().shortWindow())
                + "); fill-rate projection informational. No SMART/NVMe drive-health data is collected.";
        List<InstanceHealth> volumes = new ArrayList<>();
        for (String label : AnalyzerSupport.instancesOrSingle(ctx, MetricType.STORAGE_UTILIZATION)) {
            volumes.add(analyzeVolume(ctx, label));
        }
        return AnalyzerSupport.assemble(ctx, ComponentId.STORAGE_CAPACITY, basis, volumes,
                AnalyzerSupport.Aggregation.BLEND_MIN_MEAN, List.of(), "Storage capacity could not be assessed.");
    }

    private InstanceHealth analyzeVolume(AnalysisContext ctx, String label) {
        var recent = AnalyzerSupport.recent(ctx, MetricType.STORAGE_UTILIZATION, label);
        int minValues = Math.min(MIN_CAPACITY_SAMPLES, ctx.settings().minSamples());
        String reason = AnalyzerSupport.insufficiencyReason(ctx, MetricType.STORAGE_UTILIZATION, label, recent,
                "storage utilization", minValues);
        if (reason != null) {
            return InstanceHealth.noData(label, reason, recent.valueCount(), recent.coverage());
        }

        double[] v = recent.values();
        double util = v[v.length - 1];
        double warn = ctx.settings().thresholds().storageWarn();
        double crit = ctx.settings().thresholds().storageCritical();

        double penalty = Curves.interpolate(util, new double[][] { { warn - 15, 0 }, { warn, 25 }, { crit, 60 }, { 100, 100 } });
        int score = Curves.score(penalty);

        List<Finding> findings = new ArrayList<>();
        if (util >= crit) {
            findings.add(Finding.critical("STORAGE_NEARLY_FULL", label + " is " + AnalyzerSupport.round(util, 1)
                    + "% full (critical threshold " + crit + "%).", ev("instance", label, "utilization", util, "criticalThreshold", crit)));
        } else if (util >= warn) {
            findings.add(Finding.warning("STORAGE_HIGH_UTILIZATION", label + " is " + AnalyzerSupport.round(util, 1)
                    + "% full (warning threshold " + warn + "%).", ev("instance", label, "utilization", util, "warnThreshold", warn)));
        }

        var lv = AnalyzerSupport.longView(ctx, MetricType.STORAGE_UTILIZATION, label);
        Double growthPerDay = null;
        Double daysToFull = null;
        Double r2 = null;
        if (lv.trend().isPresent()) {
            var t = lv.trend().get();
            growthPerDay = t.perDay();
            r2 = t.rSquared();
            if (t.perDay() > 0.01 && util < 100) {
                daysToFull = (100.0 - util) / t.perDay();
                double critDays = ctx.settings().risk().storageHorizonCriticalDays();
                double warnDays = ctx.settings().risk().storageHorizonWarnDays();
                String msg = label + " is growing about " + AnalyzerSupport.round(t.perDay(), 2) + " percentage points per day; at this rate it fills in about "
                        + AnalyzerSupport.round(daysToFull, 1) + " days (linear projection, R\u00B2 " + AnalyzerSupport.round(t.rSquared(), 2) + ").";
                var evidence = ev("instance", label, "growthPerDay", t.perDay(), "daysToFull", daysToFull,
                        "rSquared", t.rSquared(), "spanHours", lv.spanHours());
                if (daysToFull <= critDays) {
                    findings.add(Finding.warning("STORAGE_FILLING_FAST", msg, evidence));
                } else if (daysToFull <= warnDays) {
                    findings.add(Finding.info("STORAGE_FILLING", msg, evidence));
                }
            }
        }

        return new InstanceHealth(label, score, Scoring.statusFor(score, ctx.settings()), v.length,
                AnalyzerSupport.round(recent.coverage(), 3),
                metrics("utilization", util, "average", Stats.mean(v), "growthPerDay", growthPerDay, "daysToFull", daysToFull,
                        "trendRSquared", r2, "historySpanHours", lv.spanHours(), "historyDensity", lv.density()),
                findings, null);
    }
}
