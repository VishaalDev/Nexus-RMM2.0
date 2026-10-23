package com.nexusrmm.health.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 2: least-squares trend. */
class LinearTrendTest {

    @Test
    void recoversAnExactLine() {
        double[] x = { 0, 1, 2, 3, 4 };
        double[] y = { 10, 12, 14, 16, 18 };
        LinearTrend t = LinearTrend.fit(x, y).orElseThrow();
        assertEquals(2.0, t.slope(), 1e-12);
        assertEquals(10.0, t.intercept(), 1e-12);
        assertEquals(1.0, t.rSquared(), 1e-12);
        assertEquals(48.0, t.perDay(), 1e-9);
        assertEquals(20.0, t.predict(5), 1e-12);
    }

    @Test
    void noisyDataHasLowerRSquared() {
        double[] x = { 0, 1, 2, 3, 4, 5 };
        double[] y = { 5, 1, 9, 2, 8, 3 };
        assertTrue(LinearTrend.fit(x, y).orElseThrow().rSquared() < 0.2);
    }

    @Test
    void refusesToFabricateATrendFromTooLittleOrDegenerateData() {
        assertTrue(LinearTrend.fit(new double[] { 0, 1 }, new double[] { 1, 2 }).isEmpty(), "two points are not a trend");
        assertTrue(LinearTrend.fit(new double[] { 3, 3, 3 }, new double[] { 1, 2, 3 }).isEmpty(), "x has no variance");
    }

    @Test
    void constantSeriesIsAnExactFlatFit() {
        LinearTrend t = LinearTrend.fit(new double[] { 0, 1, 2 }, new double[] { 7, 7, 7 }).orElseThrow();
        assertEquals(0.0, t.slope(), 1e-12);
        assertEquals(1.0, t.rSquared(), 1e-12);
    }
}
