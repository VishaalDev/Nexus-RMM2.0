package com.nexusrmm.health.config;

import com.nexusrmm.health.analysis.Scoring;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.HealthStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend test area 17: settings validation, property defaults/merging, and status cut-offs. */
class HealthSettingsTest {

    private static HealthSettings with(HealthSettings.Thresholds t) {
        HealthSettings d = HealthSettings.defaults();
        return new HealthSettings(d.collectionInterval(), d.cacheTtl(), d.windows(), d.minSamples(), d.trend(), d.anomaly(), t,
                d.scoring(), d.weights(), d.risk());
    }

    @Test
    void shippedDefaultsAreValid() {
        assertDoesNotThrow(HealthSettings::defaults);
        assertEquals(8, HealthSettings.defaults().weights().size());
    }

    @Test
    void warnAtOrAboveCriticalFailsFastWithTheOffendingValues() {
        HealthSettings.Thresholds d = HealthSettings.defaults().thresholds();
        var bad = new HealthSettings.Thresholds(90, 90, d.memoryWarn(), d.memoryCritical(), d.storageWarn(), d.storageCritical(),
                d.temperatureWarn(), d.temperatureCritical(), d.batteryLow(), d.batteryCritical());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> with(bad));
        assertTrue(ex.getMessage().contains("cpu") && ex.getMessage().contains("90.0"), ex.getMessage());
    }

    @Test
    void batteryCriticalMustBeBelowLow() {
        HealthSettings.Thresholds d = HealthSettings.defaults().thresholds();
        var bad = new HealthSettings.Thresholds(d.cpuWarn(), d.cpuCritical(), d.memoryWarn(), d.memoryCritical(), d.storageWarn(),
                d.storageCritical(), d.temperatureWarn(), d.temperatureCritical(), 10, 20);
        assertThrows(IllegalArgumentException.class, () -> with(bad));
    }

    @Test
    void misorderedScoreOrRiskCutoffsAndWindowsAreRejected() {
        HealthSettings d = HealthSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> new HealthSettings(d.collectionInterval(), d.cacheTtl(), d.windows(), d.minSamples(),
                d.trend(), d.anomaly(), d.thresholds(), new HealthSettings.Scoring(50, 60, 40, 30), d.weights(), d.risk()));
        assertThrows(IllegalArgumentException.class, () -> new HealthSettings(d.collectionInterval(), d.cacheTtl(), d.windows(), d.minSamples(),
                d.trend(), d.anomaly(), d.thresholds(), d.scoring(), d.weights(),
                new HealthSettings.Risk(50, 40, 70, 30, 7, d.risk().weights())));
        assertThrows(IllegalArgumentException.class, () -> new HealthSettings(d.collectionInterval(), d.cacheTtl(),
                new HealthSettings.Windows(Duration.ofHours(2), Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofDays(7), 1000, 100),
                d.minSamples(), d.trend(), d.anomaly(), d.thresholds(), d.scoring(), d.weights(), d.risk()));
    }

    @Test
    void statusCutoffsAreInclusiveAtTheLowerBound() {
        HealthSettings s = HealthSettings.defaults();
        assertEquals(HealthStatus.HEALTHY, Scoring.statusFor(85, s));
        assertEquals(HealthStatus.FAIR, Scoring.statusFor(84, s));
        assertEquals(HealthStatus.FAIR, Scoring.statusFor(65, s));
        assertEquals(HealthStatus.DEGRADED, Scoring.statusFor(64, s));
        assertEquals(HealthStatus.DEGRADED, Scoring.statusFor(40, s));
        assertEquals(HealthStatus.CRITICAL, Scoring.statusFor(39, s));
        assertEquals(HealthStatus.CRITICAL, Scoring.statusFor(0, s));
    }

    @Test
    void propertiesConvertToSettingsAndOverridingOneWeightKeepsTheOthers() {
        var d = HealthSettings.defaults();
        var t = d.thresholds();
        HealthProperties props = new HealthProperties(30, 12,
                new HealthProperties.Windows(Duration.ofHours(1), Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofDays(7), 20000, 1000),
                new HealthProperties.Trend(24, Duration.ofHours(24), 0.5, 0.25),
                new HealthProperties.Anomaly(3.5, 48),
                new HealthProperties.Thresholds(t.cpuWarn(), t.cpuCritical(), t.memoryWarn(), t.memoryCritical(), t.storageWarn(),
                        t.storageCritical(), t.temperatureWarn(), t.temperatureCritical(), t.batteryLow(), t.batteryCritical()),
                new HealthProperties.Scoring(85, 65, 40, 30),
                Map.of(ComponentId.CPU, 3.0),
                new HealthProperties.Risk(20, 45, 70, 30, 7, null));

        HealthSettings s = props.toSettings(Duration.ofSeconds(5));

        assertEquals(Duration.ofSeconds(5), s.collectionInterval());
        assertEquals(Duration.ofSeconds(30), s.cacheTtl());
        assertEquals(3.0, s.weight(ComponentId.CPU));
        assertEquals(2.0, s.weight(ComponentId.RELIABILITY), "unspecified weights keep their defaults");
        assertEquals(1.0, s.risk().weights().get(ComponentId.RELIABILITY), "null risk weights fall back to defaults");
    }
}
