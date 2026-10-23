package com.nexusrmm.health.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 1: descriptive statistics. */
class StatsTest {

    private static final double EPS = 1e-9;

    @Test
    void meanMedianMinMaxOnKnownData() {
        double[] v = { 2, 4, 4, 4, 5, 5, 7, 9 };
        assertEquals(5.0, Stats.mean(v), EPS);
        assertEquals(4.5, Stats.median(v), EPS);
        assertEquals(2.0, Stats.min(v), EPS);
        assertEquals(9.0, Stats.max(v), EPS);
    }

    @Test
    void sampleStandardDeviationUsesNMinusOne() {
        assertEquals(2.138089935299395, Stats.stdDev(new double[] { 2, 4, 4, 4, 5, 5, 7, 9 }), 1e-12);
        assertEquals(0.0, Stats.stdDev(new double[] { 5 }), EPS);
    }

    @Test
    void percentileInterpolatesAndDoesNotMutateInput() {
        double[] v = { 40, 10, 30, 20 };
        assertEquals(10.0, Stats.percentile(v, 0), EPS);
        assertEquals(40.0, Stats.percentile(v, 100), EPS);
        assertEquals(25.0, Stats.percentile(v, 50), EPS);
        assertEquals(37.0, Stats.percentile(v, 90), EPS);
        assertEquals(40, v[0], EPS, "input array must be left in its original order");
    }

    @Test
    void madAndFractions() {
        assertEquals(1.0, Stats.mad(new double[] { 1, 2, 3, 4, 100 }), EPS);
        double[] v = { 10, 20, 30, 40 };
        assertEquals(0.5, Stats.fractionAtOrAbove(v, 30), EPS);
        assertEquals(0.5, Stats.fractionAtOrBelow(v, 20), EPS);
        assertEquals(0.0, Stats.fractionAtOrAbove(new double[0], 1), EPS);
    }

    @Test
    void robustZIsResistantToOutliersInTheBaseline() {
        double[] baseline = { 10, 11, 9, 10, 12, 8, 10, 11, 9, 1000 }; // one wild outlier
        double z = Stats.robustZ(baseline, 30);
        assertTrue(z > 3.5, "30 is far outside a baseline centred near 10 despite the 1000 outlier, got " + z);
    }

    @Test
    void robustZFallsBackToMeanAbsDeviationAndReportsNaNWithNoSpread() {
        double[] zeroInflated = { 0, 0, 0, 0, 0, 0, 0, 0, 5, 7 }; // MAD = 0
        assertTrue(Stats.robustZ(zeroInflated, 50) > 3.5);
        assertTrue(Double.isNaN(Stats.robustZ(new double[] { 3, 3, 3, 3 }, 9)), "no spread => no anomaly claim");
    }

    @Test
    void invalidInputsAreRejectedNotSilentlyDefaulted() {
        assertThrows(IllegalArgumentException.class, () -> Stats.mean(new double[0]));
        assertThrows(IllegalArgumentException.class, () -> Stats.percentile(new double[] { 1 }, 101));
    }
}
