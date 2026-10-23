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
import static com.nexusrmm.health.analysis.AnalyzerSupport.percent;

/**
 * CPU temperature (the only temperature Module 1 can read; GPU
 * temperature is UNSUPPORTED). Penalty = curve(95th-percentile
 * temperature over the short window): (warn-15 -> 0), (warn -> 25),
 * (critical -> 60), (105 -> 100). The 95th percentile (not the maximum)
 * keeps a single sensor blip from dominating while still reflecting
 * sustained heat. When the sensor is unavailable the provider's own
 * diagnostic (e.g. missing PawnIO driver) is surfaced as the NO_DATA reason.
 * A rising multi-day trend is informational and explicitly not adjusted
 * for workload.
 */
public final class ThermalAnalyzer implements ComponentAnalyzer {

    private static final double TREND_REPORT_PER_DAY = 0.5;

    @Override
    public ComponentId component() {
        return ComponentId.THERMAL;
    }

    @Override
    public ComponentHealth analyze(AnalysisContext ctx) {
        String basis = "CPU temperature, 95th percentile over the last "
                + AnalyzerSupport.minutes(ctx.settings().windows().shortWindow())
                + ". GPU and other sensors are not available from Module 1.";
        return AnalyzerSupport.assemble(ctx, ComponentId.THERMAL, basis, List.of(analyzeCpu(ctx)),
                AnalyzerSupport.Aggregation.MIN, List.of(), "Temperature could not be assessed.");
    }

    private InstanceHealth analyzeCpu(AnalysisContext ctx) {
        var type = MetricType.CPU_TEMPERATURE;
        var recent = AnalyzerSupport.recent(ctx, type, null);
        String reason = AnalyzerSupport.insufficiencyReason(ctx, type, null, recent, "CPU temperature", ctx.settings().minSamples());
        if (reason != null) {
            return InstanceHealth.noData(null, reason, recent.valueCount(), recent.coverage());
        }

        double[] v = recent.values();
        double warn = ctx.settings().thresholds().temperatureWarn();
        double crit = ctx.settings().thresholds().temperatureCritical();
        double p95 = AnalyzerSupport.p95(v);
        double max = Stats.max(v);
        double avg = Stats.mean(v);
        double fracWarn = Stats.fractionAtOrAbove(v, warn);
        double fracCrit = Stats.fractionAtOrAbove(v, crit);

        double penalty = Curves.interpolate(p95, new double[][] { { warn - 15, 0 }, { warn, 25 }, { crit, 60 }, { 105, 100 } });
        int score = Curves.score(penalty);

        List<Finding> findings = new ArrayList<>();
        if (p95 >= crit) {
            findings.add(Finding.critical("THERMAL_CRITICAL", "CPU temperature 95th percentile is " + AnalyzerSupport.round(p95, 1)
                    + "\u00B0C (critical threshold " + crit + "\u00B0C).", ev("p95", p95, "max", max, "criticalThreshold", crit)));
        } else if (p95 >= warn) {
            findings.add(Finding.warning("THERMAL_HIGH", "CPU temperature 95th percentile is " + AnalyzerSupport.round(p95, 1)
                    + "\u00B0C (warning threshold " + warn + "\u00B0C).", ev("p95", p95, "max", max, "warnThreshold", warn)));
        }
        if (fracCrit > 0) {
            findings.add(Finding.warning("THERMAL_CRITICAL_EXCURSIONS", "Temperature reached " + crit + "\u00B0C or more in "
                    + percent(fracCrit) + "% of samples (peak " + AnalyzerSupport.round(max, 1) + "\u00B0C).",
                    ev("fractionPercent", percent(fracCrit), "max", max, "criticalThreshold", crit)));
        }

        var lv = AnalyzerSupport.longView(ctx, type, null);
        Double trendPerDay = null;
        Double trendR2 = null;
        if (lv.trend().isPresent()) {
            var t = lv.trend().get();
            trendPerDay = t.perDay();
            trendR2 = t.rSquared();
            if (t.perDay() >= TREND_REPORT_PER_DAY) {
                findings.add(Finding.info("THERMAL_RISING_TREND", "Average CPU temperature has risen about "
                        + AnalyzerSupport.round(t.perDay(), 2) + "\u00B0C per day over " + AnalyzerSupport.round(lv.spanHours() / 24.0, 1)
                        + " days (not adjusted for workload).", ev("slopePerDay", t.perDay(), "rSquared", t.rSquared())));
            }
        }

        return new InstanceHealth(null, score, Scoring.statusFor(score, ctx.settings()), v.length,
                AnalyzerSupport.round(recent.coverage(), 3),
                metrics("average", avg, "p95", p95, "max", max, "fractionAboveWarn", fracWarn, "fractionAboveCritical", fracCrit,
                        "historySpanHours", lv.spanHours(), "historyDensity", lv.density(), "trendPerDay", trendPerDay, "trendRSquared", trendR2),
                findings, null);
    }
}
