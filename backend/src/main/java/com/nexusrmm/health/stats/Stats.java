package com.nexusrmm.health.stats;

import java.util.Arrays;

/** Small, dependency-free descriptive statistics. All methods require a non-empty array unless noted. */
public final class Stats {

    /** Scales MAD to be comparable to a standard deviation under normality. */
    private static final double MAD_TO_SIGMA = 1.4826;

    private Stats() {
    }

    public static double mean(double[] v) {
        requireNonEmpty(v);
        double sum = 0;
        for (double d : v) sum += d;
        return sum / v.length;
    }

    public static double min(double[] v) {
        requireNonEmpty(v);
        double m = v[0];
        for (double d : v) m = Math.min(m, d);
        return m;
    }

    public static double max(double[] v) {
        requireNonEmpty(v);
        double m = v[0];
        for (double d : v) m = Math.max(m, d);
        return m;
    }

    /** Sample standard deviation (n-1); 0 for a single value. */
    public static double stdDev(double[] v) {
        requireNonEmpty(v);
        if (v.length == 1) return 0;
        double mu = mean(v);
        double ss = 0;
        for (double d : v) ss += (d - mu) * (d - mu);
        return Math.sqrt(ss / (v.length - 1));
    }

    public static double median(double[] v) {
        return percentile(v, 50);
    }

    /** Linear-interpolated percentile, p in [0,100]. Does not modify the input. */
    public static double percentile(double[] v, double p) {
        requireNonEmpty(v);
        if (p < 0 || p > 100) {
            throw new IllegalArgumentException("percentile must be within [0,100], got " + p);
        }
        double[] s = v.clone();
        Arrays.sort(s);
        if (s.length == 1) return s[0];
        double rank = (p / 100.0) * (s.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        return s[lo] + (s[hi] - s[lo]) * (rank - lo);
    }

    /** Median absolute deviation about the median. */
    public static double mad(double[] v) {
        double med = median(v);
        double[] dev = new double[v.length];
        for (int i = 0; i < v.length; i++) dev[i] = Math.abs(v[i] - med);
        return median(dev);
    }

    /** Share of values >= threshold, in [0,1]. Returns 0 for an empty array. */
    public static double fractionAtOrAbove(double[] v, double threshold) {
        if (v.length == 0) return 0;
        int n = 0;
        for (double d : v) if (d >= threshold) n++;
        return (double) n / v.length;
    }

    /** Share of values <= threshold, in [0,1]. Returns 0 for an empty array. */
    public static double fractionAtOrBelow(double[] v, double threshold) {
        if (v.length == 0) return 0;
        int n = 0;
        for (double d : v) if (d <= threshold) n++;
        return (double) n / v.length;
    }

    /**
     * Robust z-score of {@code observed} against {@code baseline}:
     * (observed - median) / (1.4826 * MAD). Falls back to the mean absolute
     * deviation when MAD is 0 (common for zero-inflated throughput series).
     * Returns NaN when the baseline has no spread at all - in which case no
     * anomaly claim can honestly be made and callers must not make one.
     */
    public static double robustZ(double[] baseline, double observed) {
        requireNonEmpty(baseline);
        double med = median(baseline);
        double mad = mad(baseline);
        double scale;
        if (mad > 0) {
            scale = MAD_TO_SIGMA * mad;
        } else {
            double meanAbs = 0;
            for (double d : baseline) meanAbs += Math.abs(d - med);
            meanAbs /= baseline.length;
            if (meanAbs <= 0) return Double.NaN;
            scale = 1.2533 * meanAbs; // mean-absolute-deviation -> sigma under normality
        }
        return (observed - med) / scale;
    }

    private static void requireNonEmpty(double[] v) {
        if (v == null || v.length == 0) {
            throw new IllegalArgumentException("statistic requires at least one value");
        }
    }
}
