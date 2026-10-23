package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.support.FakeHealthData;
import com.nexusrmm.provider.AvailabilityStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.nexusrmm.health.analysis.TestSupport.FIVE_MIN;
import static com.nexusrmm.health.analysis.TestSupport.NOW;
import static com.nexusrmm.health.analysis.TestSupport.WEEK_AGO;
import static com.nexusrmm.health.analysis.TestSupport.ctx;
import static com.nexusrmm.health.analysis.TestSupport.hasFinding;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 11: network analyzer. */
class NetworkAnalyzerTest {

    private static final String WIFI = "MediaTek Wi-Fi 6 MT7921 Wireless LAN Card";
    private static final MetricType RX = MetricType.NETWORK_RX_BYTES_PER_SEC;
    private static final MetricType TX = MetricType.NETWORK_TX_BYTES_PER_SEC;
    private final NetworkAnalyzer analyzer = new NetworkAnalyzer();

    private static double wobble(java.time.Instant t) {
        return 500_000 + 80_000 * Math.sin(FakeHealthData.hours(WEEK_AGO, t) * 1.1);
    }

    private FakeHealthData baseline() {
        return new FakeHealthData().history(RX, WIFI, 7, FIVE_MIN, NetworkAnalyzerTest::wobble)
                .history(TX, WIFI, 7, FIVE_MIN, NetworkAnalyzerTest::wobble);
    }

    @Test
    void usualTrafficIsHealthy() {
        ComponentHealth c = analyzer.analyze(ctx(baseline().recentConstant(RX, WIFI, 510_000).recentConstant(TX, WIFI, 490_000)));
        assertEquals(100, c.score());
    }

    @Test
    void aReceiveSurgeIsAWeakWarningCappedAtTenPoints() {
        ComponentHealth c = analyzer.analyze(ctx(baseline().recentConstant(RX, WIFI, 300_000_000).recentConstant(TX, WIFI, 500_000)));
        assertEquals(90, c.score());
        assertTrue(hasFinding(c, "NETWORK_RX_SURGE"));
    }

    @Test
    void aTrafficCollapseIsInformationalNotPenalized() {
        ComponentHealth c = analyzer.analyze(ctx(baseline().recentConstant(RX, WIFI, 0).recentConstant(TX, WIFI, 0)));
        assertEquals(100, c.score());
        assertTrue(hasFinding(c, "NETWORK_RX_QUIET"));
    }

    @Test
    void noOperationallyUpAdapterWithExplicitUnavailableRowsIsAScoredOutage() {
        FakeHealthData d = new FakeHealthData().valueless(RX, null, NOW.minus(Duration.ofHours(1)), NOW, FakeHealthData.STEP,
                AvailabilityStatus.UNAVAILABLE, "No operationally-up network adapter");
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(70, c.score());
        assertTrue(hasFinding(c, "NETWORK_NO_ACTIVE_ADAPTER"));
    }

    @Test
    void noRowsAtAllMeansTheCollectorWasNotRunningWhichIsNoDataNotAnOutage() {
        ComponentHealth c = analyzer.analyze(ctx(new FakeHealthData()));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertNull(c.score());
        assertTrue(c.findings().isEmpty());
    }

    @Test
    void adaptersWithoutABaselineAreNoDataWithAnExplanation() {
        FakeHealthData d = new FakeHealthData().recentConstant(RX, WIFI, 1_000).recentConstant(TX, WIFI, 1_000);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(HealthStatus.NO_DATA, c.status());
        assertTrue(c.noDataReason().contains("Insufficient history for a traffic baseline"), c.noDataReason());
    }

    @Test
    void anAdapterThatWentAwayIsNoDataForThatAdapterOnly() {
        FakeHealthData d = baseline().recentConstant(RX, WIFI, 500_000).recentConstant(TX, WIFI, 500_000)
                .values(RX, "Teredo", NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)), FIVE_MIN, t -> 100);
        ComponentHealth c = analyzer.analyze(ctx(d));
        assertEquals(100, c.score());
        assertEquals(HealthStatus.NO_DATA, TestSupport.instance(c, "Teredo").status());
    }
}
