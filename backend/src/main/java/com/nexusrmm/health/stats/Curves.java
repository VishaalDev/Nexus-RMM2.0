package com.nexusrmm.health.stats;

/**
 * Piecewise-linear scoring curves. Every analyzer expresses "how bad is
 * this value" as an explicit, documented list of (x, penalty) points rather
 * than an opaque formula, so thresholds stay legible and testable.
 */
public final class Curves {

    private Curves() {
    }

    /**
     * Linear interpolation through {@code points} ({x0,y0},{x1,y1},...),
     * clamped to the first/last y outside the covered range. Points must be
     * strictly increasing in x.
     */
    public static double interpolate(double x, double[][] points) {
        if (points.length == 0) {
            throw new IllegalArgumentException("a curve needs at least one point");
        }
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("x must not be NaN");
        }
        if (x <= points[0][0]) return points[0][1];
        for (int i = 1; i < points.length; i++) {
            double x0 = points[i - 1][0], y0 = points[i - 1][1];
            double x1 = points[i][0], y1 = points[i][1];
            if (x1 <= x0) {
                throw new IllegalArgumentException("curve x values must be strictly increasing");
            }
            if (x <= x1) {
                return y0 + (y1 - y0) * ((x - x0) / (x1 - x0));
            }
        }
        return points[points.length - 1][1];
    }

    /** 0 at or below {@code start}, {@code max} at or above {@code end}, linear between. */
    public static double ramp(double x, double start, double end, double max) {
        if (end <= start) {
            throw new IllegalArgumentException("ramp end must exceed start");
        }
        return interpolate(x, new double[][] { { start, 0 }, { end, max } });
    }

    /** 100 minus the (clamped) total penalty, rounded to a whole 0..100 score. */
    public static int score(double totalPenalty) {
        double s = 100.0 - totalPenalty;
        return (int) Math.round(Math.max(0, Math.min(100, s)));
    }
}
