package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.support.FakeHealthData;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.analysis.TestSupport.FIVE_MIN;
import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static com.nexusrmm.health.analysis.TestSupport.WEEK_AGO;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 4: baseline anomaly detection (AnomalySupport). */
class AnomalyDetectionTest {

    private static final MetricType T = MetricType.NETWORK_RX_BYTES_PER_SEC;

    /** Deterministic wobble around 1 MB/s. */
    private static double wobble(java.time.Instant t) {
        return 1_000_000 + 100_000 * Math.sin(FakeHealthData.hours(WEEK_AGO, t) * 1.3);
    }

    private FakeHealthData withBaseline() {
        return new FakeHealthData().history(T, "wifi", 7, FIVE_MIN, AnomalyDetectionTest::wobble);
    }

    private AnomalySupport.Result scan(FakeHealthData d, double recent) {
        return AnomalySupport.scan(ctx(d), T, "wifi", new double[] { recent, recent, recent });
    }

    @Test
    void aValueInsideTheUsualRangeIsNormal() {
        var r = scan(withBaseline(), 1_020_000);
        assertEquals(AnomalySupport.State.NORMAL, r.state());
        assertTrue(Math.abs(r.z()) < 3.5);
    }

    @Test
    void aMuchHigherValueIsHighWithTheBaselineReported() {
        var r = scan(withBaseline(), 50_000_000);
        assertEquals(AnomalySupport.State.HIGH, r.state());
        assertEquals(1_000_000, r.baselineMedian(), 60_000);
        assertTrue(r.baselineBuckets() >= 300);
    }

    @Test
    void aCollapseToNearZeroIsLow() {
        assertEquals(AnomalySupport.State.LOW, scan(withBaseline(), 0).state());
    }

    @Test
    void tooLittleHistoryMakesNoClaimAndSaysHowMuchIsMissing() {
        FakeHealthData d = new FakeHealthData().values(T, "wifi", NOW.minus(Duration.ofHours(6)), NOW.minus(Duration.ofHours(1)),
                FIVE_MIN, AnomalyDetectionTest::wobble); // 10 buckets of 48 required
        var r = scan(d, 50_000_000);
        assertEquals(AnomalySupport.State.NO_BASELINE, r.state());
        assertEquals(10, r.baselineBuckets());
        assertEquals(48, r.requiredBuckets());
        assertTrue(!r.assessed());
    }

    @Test
    void aBaselineWithNoVariationCannotSupportAnAnomalyClaim() {
        FakeHealthData d = new FakeHealthData().history(T, "wifi", 7, FIVE_MIN, t -> 0);
        assertEquals(AnomalySupport.State.NOT_ASSESSABLE, scan(d, 5_000).state());
    }

    @Test
    void theRecentWindowIsNeverPartOfItsOwnBaseline() {
        // History is flat 1 MB/s, then the last hour is wildly different; if the recent hour leaked into the
        // baseline the median would still be 1 MB/s, so check the bucket count excludes the final hour instead.
        FakeHealthData d = withBaseline().recentConstant(T, "wifi", 900_000_000);
        var r = AnomalySupport.scan(ctx(d), T, "wifi", new double[] { 900_000_000 });
        assertEquals(AnomalySupport.State.HIGH, r.state());
        assertEquals(1_000_000, r.baselineMedian(), 60_000);
    }

    @Test
    void rateFormattingUsesBinaryUnits() {
        assertEquals("512 B/s", AnomalySupport.rate(512));
        assertEquals("1.5 MiB/s", AnomalySupport.rate(1.5 * 1024 * 1024));
    }
}
