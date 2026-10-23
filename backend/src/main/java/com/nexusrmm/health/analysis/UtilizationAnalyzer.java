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
 * Shared logic for load-type percentages (CPU, RAM): how heavily and how
 * persistently the resource is used over the short window.
 *
 * Penalty = curve(average utilization) + ramp(share of samples at/above
 * the critical threshold). The average curve is
 * (warn-20 -> 0), (warn -> 25), (critical -> 50), (100 -> 60); the
 * saturation ramp is 0 at 5% of samples and 40 at 60% of samples. A
 * resource that is pegged continuously therefore scores ~10, while a
 * brief spike to 100% inside an otherwise idle hour barely moves the
 * score. Rising long-term trend is reported as INFO only (it is a risk
 * signal, not a present fault) and feeds the risk engine.
 */
abstract class UtilizationAnalyzer implements ComponentAnalyzer {

    /** Percentage points per day above which a significant rising trend is worth reporting. */
    private static final double TREND_REPORT_PER_DAY = 1.0;

    private static final double SATURATION_RAMP_START = 0.05;
    private static final double SATURATION_RAMP_END = 0.60;
    private static final double SATURATION_MAX_PENALTY = 40;

    private final ComponentId id;
    private final MetricType type;
    private final String what;

    UtilizationAnalyzer(ComponentId id, MetricType type, String what) {
        this.id = id;
        this.type = type;
        this.what = what;
    }

    protected abstract double warn(AnalysisContext ctx);

    protected abstract double critical(AnalysisContext ctx);

    @Override
    public ComponentId component() {
        return id;
    }

    @Override
    public ComponentHealth analyze(AnalysisContext ctx) {
        String basis = what + " over the last " + AnalyzerSupport.minutes(ctx.settings().windows().shortWindow())
                + " (average and share of samples at/above the critical threshold); long-term trend informational only.";
        InstanceHealth ih = analyzeInstance(ctx);
        return AnalyzerSupport.assemble(ctx, id, basis, List.of(ih), AnalyzerSupport.Aggregation.MIN, List.of(),
                what + " could not be assessed.");
    }

    private InstanceHealth analyzeInstance(AnalysisContext ctx) {
        var recent = AnalyzerSupport.recent(ctx, type, null);
        String reason = AnalyzerSupport.insufficiencyReason(ctx, type, null, recent, what, ctx.settings().minSamples());
        if (reason != null) {
            return InstanceHealth.noData(null, reason, recent.valueCount(), recent.coverage());
        }

        double[] v = recent.values();
        double warn = warn(ctx);
        double crit = critical(ctx);
        double avg = Stats.mean(v);
        double p95 = AnalyzerSupport.p95(v);
        double max = Stats.max(v);
        double sat = Stats.fractionAtOrAbove(v, crit);

        double penalty = Curves.interpolate(avg, new double[][] { { warn - 20, 0 }, { warn, 25 }, { crit, 50 }, { 100, 60 } })
                + Curves.ramp(sat, SATURATION_RAMP_START, SATURATION_RAMP_END, SATURATION_MAX_PENALTY);
        int score = Curves.score(penalty);

        List<Finding> findings = new ArrayList<>();
        if (avg >= crit) {
            findings.add(Finding.critical(id + "_SUSTAINED_CRITICAL",
                    "Average " + what + " is " + AnalyzerSupport.round(avg, 1) + "% (critical threshold " + crit + "%).",
                    ev("average", avg, "criticalThreshold", crit)));
        } else if (avg >= warn) {
            findings.add(Finding.warning(id + "_SUSTAINED_HIGH",
                    "Average " + what + " is " + AnalyzerSupport.round(avg, 1) + "% (warning threshold " + warn + "%).",
                    ev("average", avg, "warnThreshold", warn)));
        }
        if (sat >= 0.5) {
            findings.add(Finding.critical(id + "_SATURATED",
                    "At or above " + crit + "% for " + percent(sat) + "% of the window.",
                    ev("saturationPercent", percent(sat), "criticalThreshold", crit)));
        } else if (sat >= 0.25) {
            findings.add(Finding.warning(id + "_FREQUENT_SATURATION",
                    "At or above " + crit + "% for " + percent(sat) + "% of the window.",
                    ev("saturationPercent", percent(sat), "criticalThreshold", crit)));
        }

        var lv = AnalyzerSupport.longView(ctx, type, null);
        Double trendPerDay = null;
        Double trendR2 = null;
        if (lv.trend().isPresent()) {
            var t = lv.trend().get();
            trendPerDay = t.perDay();
            trendR2 = t.rSquared();
            if (t.perDay() >= TREND_REPORT_PER_DAY) {
                findings.add(Finding.info(id + "_RISING_TREND",
                        what + " has been rising about " + AnalyzerSupport.round(t.perDay(), 1) + " percentage points per day over "
                                + AnalyzerSupport.round(lv.spanHours() / 24.0, 1) + " days (R\u00B2 " + AnalyzerSupport.round(t.rSquared(), 2) + ").",
                        ev("slopePerDay", t.perDay(), "rSquared", t.rSquared(), "spanHours", lv.spanHours())));
            }
        }

        return new InstanceHealth(null, score, Scoring.statusFor(score, ctx.settings()), v.length,
                AnalyzerSupport.round(recent.coverage(), 3),
                metrics("average", avg, "p95", p95, "max", max, "saturationFraction", sat, "historySpanHours", lv.spanHours(), "historyDensity", lv.density(),
                        "trendPerDay", trendPerDay, "trendRSquared", trendR2),
                findings, null);
    }
}
