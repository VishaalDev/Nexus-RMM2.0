package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.stats.Curves;
import com.nexusrmm.health.stats.LinearTrend;
import com.nexusrmm.health.stats.Stats;
import com.nexusrmm.provider.AvailabilityStatus;

import java.util.ArrayList;
import java.util.List;

import static com.nexusrmm.health.analysis.AnalyzerSupport.ev;
import static com.nexusrmm.health.analysis.AnalyzerSupport.metrics;
import static com.nexusrmm.health.analysis.AnalyzerSupport.percent;

/**
 * Battery USAGE health, per power source. Module 1 collects charge level
 * only - not design/full-charge capacity, cycle count or wear - so true
 * battery health cannot be known and the basis says so. What is scored is
 * a usage pattern that does age Li-ion cells: how much of the long window
 * the pack spent at or below the low-charge threshold, plus the present
 * state if it is low AND still discharging.
 *
 * Penalty = ramp(share of long-window buckets at/below the low threshold:
 * 5% -> 0, 50% -> 40) + (current at/below critical and discharging: 15, or
 * at/below low and discharging: 8). On a machine with no battery the
 * provider reports NOT_APPLICABLE and this component is NO_DATA - never a
 * perfect score.
 */
public final class BatteryAnalyzer implements ComponentAnalyzer {

    private static final double DWELL_RAMP_START = 0.05;
    private static final double DWELL_RAMP_END = 0.50;
    private static final double DWELL_MAX_PENALTY = 40;
    private static final double LOW_NOW_PENALTY = 8;
    private static final double CRITICAL_NOW_PENALTY = 15;
    /** Percentage points per hour below which charge is considered to be draining. */
    private static final double DRAIN_SLOPE_PER_HOUR = -0.5;
    private static final int MIN_DWELL_BUCKETS = 12;

    @Override
    public ComponentId component() {
        return ComponentId.BATTERY;
    }

    @Override
    public ComponentHealth analyze(AnalysisContext ctx) {
        String basis = "Battery charge-level usage pattern only (time spent at/below the low threshold over the long window, "
                + "plus current state). Wear, capacity and cycle count are not collected, so battery wear is not assessed.";
        List<InstanceHealth> sources = new ArrayList<>();
        for (String label : AnalyzerSupport.instancesOrSingle(ctx, MetricType.BATTERY_LEVEL)) {
            sources.add(analyzeSource(ctx, label));
        }
        return AnalyzerSupport.assemble(ctx, ComponentId.BATTERY, basis, sources, AnalyzerSupport.Aggregation.MIN,
                List.of(), "Battery could not be assessed.");
    }

    private InstanceHealth analyzeSource(AnalysisContext ctx, String label) {
        var type = MetricType.BATTERY_LEVEL;
        var recent = AnalyzerSupport.recent(ctx, type, label);

        // Surface "not applicable" explicitly: a desktop is not a battery with a good score.
        if (recent.valueCount() == 0 && !recent.rows().isEmpty()
                && recent.rows().get(recent.rows().size() - 1).status() == AvailabilityStatus.NOT_APPLICABLE) {
            String diagnostic = ctx.data().latest(type, label).map(l -> l.diagnostic()).orElse(null);
            return InstanceHealth.noData(label, "Not applicable: this device reports no battery"
                    + (diagnostic == null || diagnostic.isBlank() ? "." : " (" + diagnostic + ")."), 0, recent.coverage());
        }

        String reason = AnalyzerSupport.insufficiencyReason(ctx, type, label, recent, "battery level", ctx.settings().minSamples());
        if (reason != null) {
            return InstanceHealth.noData(label, reason, recent.valueCount(), recent.coverage());
        }

        double[] v = recent.values();
        double now = v[v.length - 1];
        double low = ctx.settings().thresholds().batteryLow();
        double critical = ctx.settings().thresholds().batteryCritical();

        double[] hours = new double[recent.rows().size()];
        double[] levels = new double[recent.rows().size()];
        int n = 0;
        long first = recent.rows().get(0).time().toEpochMilli();
        for (var row : recent.rows()) {
            if (row.hasValue()) {
                hours[n] = (row.time().toEpochMilli() - first) / 3_600_000.0;
                levels[n] = row.value();
                n++;
            }
        }
        var drain = LinearTrend.fit(java.util.Arrays.copyOf(hours, n), java.util.Arrays.copyOf(levels, n));
        boolean draining = drain.isPresent() && drain.get().slope() <= DRAIN_SLOPE_PER_HOUR;

        var lv = AnalyzerSupport.longView(ctx, type, label);
        double dwell = 0;
        boolean dwellKnown = lv.buckets().size() >= MIN_DWELL_BUCKETS;
        if (dwellKnown) {
            double[] avgs = lv.buckets().stream().mapToDouble(b -> b.avg()).toArray();
            dwell = Stats.fractionAtOrBelow(avgs, low);
        }

        double penalty = dwellKnown ? Curves.ramp(dwell, DWELL_RAMP_START, DWELL_RAMP_END, DWELL_MAX_PENALTY) : 0;
        if (draining && now <= critical) {
            penalty += CRITICAL_NOW_PENALTY;
        } else if (draining && now <= low) {
            penalty += LOW_NOW_PENALTY;
        }
        int score = Curves.score(penalty);

        List<Finding> findings = new ArrayList<>();
        if (draining && now <= critical) {
            findings.add(Finding.critical("BATTERY_CRITICALLY_LOW", label + " is at " + AnalyzerSupport.round(now, 0)
                    + "% and discharging.", ev("instance", label, "level", now)));
        } else if (draining && now <= low) {
            findings.add(Finding.warning("BATTERY_LOW", label + " is at " + AnalyzerSupport.round(now, 0)
                    + "% and discharging.", ev("instance", label, "level", now)));
        }
        if (dwellKnown && dwell >= 0.2) {
            findings.add(Finding.warning("BATTERY_FREQUENT_DEEP_DISCHARGE", label + " spent " + percent(dwell)
                    + "% of the long window at or below " + low + "% charge (frequent deep discharge accelerates wear).",
                    ev("instance", label, "dwellPercent", percent(dwell), "lowThreshold", low)));
        }

        return new InstanceHealth(label, score, Scoring.statusFor(score, ctx.settings()), v.length,
                AnalyzerSupport.round(recent.coverage(), 3),
                metrics("level", now, "lowDwellFraction", dwellKnown ? dwell : null, "draining", draining ? 1.0 : 0.0,
                        "historySpanHours", lv.spanHours(), "historyDensity", lv.density()),
                findings, null);
    }
}
