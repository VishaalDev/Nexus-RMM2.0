package com.nexusrmm.health.config;

import com.nexusrmm.health.model.ComponentId;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Framework-free, immutable configuration for the analysis and risk
 * engines. The engines depend on this record - never on Spring - so they
 * can be unit-tested with plain JUnit. {@link HealthProperties} binds
 * {@code nexus.health.*} from application.yml and converts into it, so
 * every tunable still lives in application.yml, not in code.
 */
public record HealthSettings(
        Duration collectionInterval,
        Duration cacheTtl,
        Windows windows,
        int minSamples,
        Trend trend,
        Anomaly anomaly,
        Thresholds thresholds,
        Scoring scoring,
        Map<ComponentId, Double> weights,
        Risk risk
) {

    public record Windows(Duration shortWindow, Duration longWindow, Duration bucket, Duration eventWindow,
                          int maxRawSamples, int maxEvents) {
    }

    /**
     * A linear trend is only reported when it clears all four bars. {@code minDensity} is the share of
     * bucket slots between the first and last populated bucket that actually hold data: it stops a few
     * short sessions scattered over many days from posing as a continuous history.
     */
    public record Trend(int minBuckets, Duration minSpan, double minRSquared, double minDensity) {
    }

    public record Anomaly(double zThreshold, int minBaselineBuckets) {
    }

    public record Thresholds(double cpuWarn, double cpuCritical,
                             double memoryWarn, double memoryCritical,
                             double storageWarn, double storageCritical,
                             double temperatureWarn, double temperatureCritical,
                             double batteryLow, double batteryCritical) {
    }

    /** Score cut-offs: score >= healthyMin is HEALTHY, >= fairMin FAIR, >= degradedMin DEGRADED, else CRITICAL. */
    public record Scoring(int healthyMin, int fairMin, int degradedMin, int worstComponentMargin) {
    }

    public record Risk(int lowMax, int moderateMax, int highMax,
                       double storageHorizonWarnDays, double storageHorizonCriticalDays,
                       Map<ComponentId, Double> weights) {
    }

    public HealthSettings {
        requireOrdered("cpu", thresholds.cpuWarn(), thresholds.cpuCritical());
        requireOrdered("memory", thresholds.memoryWarn(), thresholds.memoryCritical());
        requireOrdered("storage", thresholds.storageWarn(), thresholds.storageCritical());
        requireOrdered("temperature", thresholds.temperatureWarn(), thresholds.temperatureCritical());
        requireOrdered("battery (critical must be below low)", thresholds.batteryCritical(), thresholds.batteryLow());
        requireOrdered("storage horizon days (critical must be below warn)", risk.storageHorizonCriticalDays(), risk.storageHorizonWarnDays());
        if (!(scoring.healthyMin() > scoring.fairMin() && scoring.fairMin() > scoring.degradedMin() && scoring.degradedMin() > 0)) {
            throw new IllegalArgumentException("nexus.health.scoring cut-offs must satisfy healthy-min > fair-min > degraded-min > 0");
        }
        if (!(risk.lowMax() < risk.moderateMax() && risk.moderateMax() < risk.highMax() && risk.highMax() < 100)) {
            throw new IllegalArgumentException("nexus.health.risk cut-offs must satisfy low-max < moderate-max < high-max < 100");
        }
        if (minSamples < 1 || windows.shortWindow().isZero() || windows.longWindow().compareTo(windows.shortWindow()) <= 0
                || windows.bucket().isZero() || collectionInterval.isZero()) {
            throw new IllegalArgumentException("nexus.health windows must satisfy long-window > short-window > 0, bucket > 0, min-samples >= 1");
        }
        weights = Map.copyOf(weights);
        risk = new Risk(risk.lowMax(), risk.moderateMax(), risk.highMax(), risk.storageHorizonWarnDays(),
                risk.storageHorizonCriticalDays(), Map.copyOf(risk.weights()));
    }

    private static void requireOrdered(String what, double lower, double higher) {
        if (!(lower < higher)) {
            throw new IllegalArgumentException("nexus.health.thresholds: " + what + " thresholds must satisfy lower < higher (got "
                    + lower + " and " + higher + ")");
        }
    }

    public double weight(ComponentId id) {
        return weights.getOrDefault(id, 0.0);
    }

    /** The shipped defaults; identical to the values in application.yml. */
    public static HealthSettings defaults() {
        Map<ComponentId, Double> w = new EnumMap<>(ComponentId.class);
        w.put(ComponentId.CPU, 1.0);
        w.put(ComponentId.MEMORY, 1.0);
        w.put(ComponentId.STORAGE_CAPACITY, 1.5);
        w.put(ComponentId.STORAGE_IO, 0.5);
        w.put(ComponentId.THERMAL, 1.5);
        w.put(ComponentId.BATTERY, 0.75);
        w.put(ComponentId.NETWORK, 0.5);
        w.put(ComponentId.RELIABILITY, 2.0);

        Map<ComponentId, Double> rw = new EnumMap<>(ComponentId.class);
        rw.put(ComponentId.RELIABILITY, 1.0);
        rw.put(ComponentId.STORAGE_CAPACITY, 0.9);
        rw.put(ComponentId.THERMAL, 0.8);
        rw.put(ComponentId.MEMORY, 0.5);
        rw.put(ComponentId.CPU, 0.4);
        rw.put(ComponentId.BATTERY, 0.4);

        return new HealthSettings(
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                new Windows(Duration.ofHours(1), Duration.ofDays(7), Duration.ofMinutes(30), Duration.ofDays(7), 20_000, 1_000),
                12,
                new Trend(24, Duration.ofHours(24), 0.5, 0.25),
                new Anomaly(3.5, 48),
                new Thresholds(75, 90, 80, 92, 85, 95, 80, 90, 20, 10),
                new Scoring(85, 65, 40, 30),
                w,
                new Risk(20, 45, 70, 30, 7, rw)
        );
    }
}
