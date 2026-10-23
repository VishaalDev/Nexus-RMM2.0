package com.nexusrmm.health.stats;

import java.util.Optional;

/**
 * Ordinary least squares line y = intercept + slope * x. With x in hours,
 * {@code slope} is "units per hour"; {@link #perDay()} scales it.
 */
public record LinearTrend(double slope, double intercept, double rSquared, int n) {

    public double perDay() {
        return slope * 24.0;
    }

    public double predict(double x) {
        return intercept + slope * x;
    }

    /**
     * Fits a line. Empty when there are fewer than 3 points or x has no
     * variance - a trend is never fabricated from two points. When y is
     * constant the fit is exact (slope 0, R^2 defined as 1).
     */
    public static Optional<LinearTrend> fit(double[] x, double[] y) {
        if (x == null || y == null || x.length != y.length) {
            throw new IllegalArgumentException("x and y must be the same length");
        }
        int n = x.length;
        if (n < 3) return Optional.empty();

        double mx = Stats.mean(x);
        double my = Stats.mean(y);
        double sxx = 0, sxy = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        if (sxx == 0) return Optional.empty();

        double slope = sxy / sxx;
        double intercept = my - slope * mx;
        double r2 = syy == 0 ? 1.0 : (sxy * sxy) / (sxx * syy);
        return Optional.of(new LinearTrend(slope, intercept, r2, n));
    }
}
