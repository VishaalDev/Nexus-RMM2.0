package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.model.Severity;
import com.nexusrmm.health.provider.BucketPoint;
import com.nexusrmm.health.provider.LatestReading;
import com.nexusrmm.health.provider.SamplePoint;
import com.nexusrmm.health.stats.LinearTrend;
import com.nexusrmm.health.stats.Stats;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Helpers shared by the eight analyzers. Package-private: not part of the module's API. */
final class AnalyzerSupport {

    private AnalyzerSupport() {
    }

    /** The recent (short-window) raw view of one series. */
    record Recent(List<SamplePoint> rows, double[] values, double coverage) {
        int valueCount() {
            return values.length;
        }
    }

    /** The long-window bucketed view of one series, plus a trend only if it clears every eligibility bar. */
    record LongView(List<BucketPoint> buckets, double spanHours, double density, Optional<LinearTrend> trend) {
    }

    static Recent recent(AnalysisContext ctx, MetricType type, String instance) {
        List<SamplePoint> rows = ctx.data().samples(type, instance, ctx.shortStart(), ctx.settings().windows().maxRawSamples());
        double[] values = rows.stream().filter(SamplePoint::hasValue).mapToDouble(SamplePoint::value).toArray();
        double coverage = Math.min(1.0, (double) values.length / ctx.expectedShortSamples());
        return new Recent(rows, values, coverage);
    }

    /**
     * Why a series cannot be assessed, in plain language, built from what
     * is actually stored (including the provider's own diagnostic).
     * Returns null when there is enough valid data.
     */
    static String insufficiencyReason(AnalysisContext ctx, MetricType type, String instance, Recent r, String what, int minValues) {
        if (r.valueCount() >= minValues) {
            return null;
        }
        long windowMinutes = ctx.settings().windows().shortWindow().toMinutes();
        Optional<LatestReading> latest = ctx.data().latest(type, instance);

        if (r.rows().isEmpty()) {
            if (latest.isEmpty()) {
                // Deliberately does not claim "never stored": a multi-instance metric whose instances all fell
                // outside the long window is indistinguishable here from one that was never stored.
                return "No " + what + " samples found in the last " + ctx.settings().windows().longWindow().toDays()
                        + " days (nothing stored yet, or the collector has not run since).";
            }
            LatestReading l = latest.get();
            return "No " + what + " samples in the last " + windowMinutes + " min (collector stopped or machine off?). "
                    + "Latest stored sample: " + l.time() + " [" + l.status() + "]" + diag(l.diagnostic()) + ".";
        }
        if (r.valueCount() == 0) {
            SamplePoint last = r.rows().get(r.rows().size() - 1);
            String diagnostic = latest.map(LatestReading::diagnostic).orElse(null);
            return what + " is reporting " + last.status() + " with no usable value" + diag(diagnostic) + ".";
        }
        return "Insufficient data: " + r.valueCount() + " valid " + what + " sample(s) in the last " + windowMinutes
                + " min; at least " + minValues + " are required.";
    }

    private static String diag(String diagnostic) {
        return diagnostic == null || diagnostic.isBlank() ? "" : " - " + diagnostic;
    }

    static LongView longView(AnalysisContext ctx, MetricType type, String instance) {
        List<BucketPoint> all = ctx.data().buckets(type, instance, ctx.longStart(), ctx.now(), ctx.settings().windows().bucket());
        List<BucketPoint> buckets = new ArrayList<>();
        for (BucketPoint b : all) {
            if (b.valueCount() > 0) buckets.add(b);
        }
        if (buckets.isEmpty()) {
            return new LongView(buckets, 0, 0, Optional.empty());
        }
        long firstMs = buckets.get(0).start().toEpochMilli();
        long lastMs = buckets.get(buckets.size() - 1).start().toEpochMilli();
        double spanHours = (lastMs - firstMs) / 3_600_000.0;
        double bucketHours = Math.max(1, ctx.settings().windows().bucket().toMinutes()) / 60.0;
        double density = Math.min(1.0, buckets.size() / (spanHours / bucketHours + 1.0));

        double[] x = new double[buckets.size()];
        double[] y = new double[buckets.size()];
        for (int i = 0; i < x.length; i++) {
            x[i] = (buckets.get(i).start().toEpochMilli() - firstMs) / 3_600_000.0;
            y[i] = buckets.get(i).avg();
        }

        var trendCfg = ctx.settings().trend();
        Optional<LinearTrend> trend = Optional.empty();
        boolean spanOk = spanHours >= trendCfg.minSpan().toMinutes() / 60.0;
        if (buckets.size() >= trendCfg.minBuckets() && spanOk && density >= trendCfg.minDensity()) {
            trend = LinearTrend.fit(x, y).filter(t -> t.rSquared() >= trendCfg.minRSquared());
        }
        return new LongView(buckets, spanHours, round(density, 3), trend);
    }

    /** Instance labels seen in the long window, or a single null (the single-instance case) if none. */
    static List<String> instancesOrSingle(AnalysisContext ctx, MetricType... types) {
        java.util.TreeSet<String> labels = new java.util.TreeSet<>();
        for (MetricType t : types) {
            labels.addAll(ctx.data().instances(t, ctx.longStart()));
        }
        if (labels.isEmpty()) {
            return Collections.singletonList(null);
        }
        return new ArrayList<>(labels);
    }

    enum Aggregation { MIN, MEAN, BLEND_MIN_MEAN }

    /** Folds instance results into one component result. NO_DATA instances never contribute a score. */
    static ComponentHealth assemble(AnalysisContext ctx, ComponentId id, String basis, List<InstanceHealth> instances,
                                    Aggregation mode, List<Finding> extraFindings, String allNoDataFallback) {
        double weight = ctx.settings().weight(id);
        List<InstanceHealth> scored = instances.stream().filter(i -> i.score() != null).toList();

        List<Finding> findings = new ArrayList<>();
        for (InstanceHealth i : instances) {
            findings.addAll(i.findings());
        }
        findings.addAll(extraFindings);
        findings.sort((a, b) -> b.severity().compareTo(a.severity()));

        if (scored.isEmpty()) {
            String reason = instances.stream().map(InstanceHealth::noDataReason).filter(r -> r != null)
                    .map(r -> r).distinct().reduce((a, b) -> a + " | " + b).orElse(allNoDataFallback);
            ComponentHealth base = ComponentHealth.noData(id, weight, basis, reason, instances);
            return new ComponentHealth(base.component(), base.label(), null, HealthStatus.NO_DATA, weight, 0, 0.0,
                    basis, instances, findings, reason);
        }

        double min = scored.stream().mapToInt(InstanceHealth::score).min().orElse(0);
        double mean = scored.stream().mapToInt(InstanceHealth::score).average().orElse(0);
        double combined = switch (mode) {
            case MIN -> min;
            case MEAN -> mean;
            case BLEND_MIN_MEAN -> 0.5 * min + 0.5 * mean;
        };
        int score = (int) Math.round(combined);
        int samples = scored.stream().mapToInt(InstanceHealth::sampleCount).sum();
        double coverage = scored.stream().mapToDouble(InstanceHealth::dataCoverage).average().orElse(0);

        return new ComponentHealth(id, id.label(), score, Scoring.statusFor(score, ctx.settings()), weight, samples,
                round(coverage, 3), basis, instances, findings, null);
    }

    static Map<String, Object> ev(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object v = keyValues[i + 1];
            if (v instanceof Double d) v = round(d, 2);
            m.put(String.valueOf(keyValues[i]), v);
        }
        return m;
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    static Map<String, Double> metrics(Object... keyValues) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object v = keyValues[i + 1];
            if (v instanceof Number n && !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue())) {
                m.put(String.valueOf(keyValues[i]), round(n.doubleValue(), 3));
            }
        }
        return m;
    }

    static double percent(double fraction) {
        return round(fraction * 100.0, 1);
    }

    static String minutes(Duration d) {
        return d.toMinutes() + " min";
    }

    static double p95(double[] v) {
        return Stats.percentile(v, 95);
    }

    static boolean atLeast(Severity s, Severity floor) {
        return s.compareTo(floor) >= 0;
    }
}
