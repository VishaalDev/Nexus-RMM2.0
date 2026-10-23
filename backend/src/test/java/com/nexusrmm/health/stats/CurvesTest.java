package com.nexusrmm.health.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Backend test area 3: scoring curves. (Status mapping is covered in HealthSettingsTest.) */
class CurvesTest {

    private static final double[][] CURVE = { { 50, 0 }, { 75, 25 }, { 90, 50 }, { 100, 60 } };

    @Test
    void interpolatesBetweenPointsAndClampsOutsideTheRange() {
        assertEquals(0.0, Curves.interpolate(10, CURVE), 1e-12);
        assertEquals(0.0, Curves.interpolate(50, CURVE), 1e-12);
        assertEquals(12.5, Curves.interpolate(62.5, CURVE), 1e-12);
        assertEquals(25.0, Curves.interpolate(75, CURVE), 1e-12);
        assertEquals(55.0, Curves.interpolate(95, CURVE), 1e-12);
        assertEquals(60.0, Curves.interpolate(500, CURVE), 1e-12);
    }

    @Test
    void rampIsZeroBelowStartAndMaxAboveEnd() {
        assertEquals(0.0, Curves.ramp(0.01, 0.05, 0.60, 40), 1e-12);
        assertEquals(40.0, Curves.ramp(0.9, 0.05, 0.60, 40), 1e-12);
        assertEquals(20.0, Curves.ramp(0.325, 0.05, 0.60, 40), 1e-9);
    }

    @Test
    void scoreIsClampedAndRounded() {
        assertEquals(100, Curves.score(-5));
        assertEquals(0, Curves.score(250));
        assertEquals(67, Curves.score(33.33));
    }

    @Test
    void malformedCurvesFailLoudly() {
        assertThrows(IllegalArgumentException.class, () -> Curves.interpolate(5, new double[][] { { 1, 0 }, { 1, 5 } }));
        assertThrows(IllegalArgumentException.class, () -> Curves.ramp(1, 5, 5, 10));
        assertThrows(IllegalArgumentException.class, () -> Curves.interpolate(Double.NaN, CURVE));
    }
}
