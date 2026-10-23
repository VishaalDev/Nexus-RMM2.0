package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.provider.BucketPoint;
import com.nexusrmm.health.stats.Stats;

import java.time.Instant;
import java.util.List;

/**
 * Compares the recent window against the machine's OWN earlier history
 * (robust z-score: median and MAD of the long-window bucket averages that
 * end where the recent window begins). Throughput has no inherent
 * healthy or unhealthy level, so anomaly-versus-baseline is the only
 * honest signal available for it.
 *
 * No claim is made unless there is enough baseline ({@code
 * nexus.health.anomaly.min-baseline-buckets} populated buckets) AND the
 * baseline has some spread; otherwise the result says why it cannot judge.
 */
final class AnomalySupport {

    enum State { NO_BASELINE, NOT_ASSESSABLE, NORMAL, HIGH, LOW }

    record Result(State state, double z, double recentMean, double baselineMedian, int baselineBuckets, int requiredBuckets) {
        boolean assessed() {
            return state == State.NORMAL || state == State.HIGH || state == State.LOW;
        }
    }

    private AnomalySupport() {
    }

    static Result scan(AnalysisContext ctx, MetricType type, String instance, double[] recentValues) {
        int required = ctx.settings().anomaly().minBaselineBuckets();
        Instant baselineEnd = ctx.shortStart();
        List<BucketPoint> buckets = ctx.data().buckets(type, instance, ctx.longStart(), baselineEnd, ctx.settings().windows().bucket());
        double[] baseline = buckets.stream().filter(b -> b.valueCount() > 0).mapToDouble(BucketPoint::avg).toArray();

        double recentMean = recentValues.length == 0 ? Double.NaN : Stats.mean(recentValues);
        if (baseline.length < required || recentValues.length == 0) {
            return new Result(State.NO_BASELINE, Double.NaN, recentMean, Double.NaN, baseline.length, required);
        }
        double baselineMedian = Stats.median(baseline);
        double z = Stats.robustZ(baseline, recentMean);
        if (Double.isNaN(z)) {
            return new Result(State.NOT_ASSESSABLE, Double.NaN, recentMean, baselineMedian, baseline.length, required);
        }
        double threshold = ctx.settings().anomaly().zThreshold();
        State state = z >= threshold ? State.HIGH : (z <= -threshold ? State.LOW : State.NORMAL);
        return new Result(state, z, recentMean, baselineMedian, baseline.length, required);
    }

    /** Human-readable bytes/second using binary units. */
    static String rate(double bytesPerSecond) {
        String[] units = { "B/s", "KiB/s", "MiB/s", "GiB/s" };
        double v = bytesPerSecond;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        String number = u == 0 ? String.valueOf(Math.round(v)) : String.valueOf(AnalyzerSupport.round(v, 1));
        return number + " " + units[u];
    }
}
