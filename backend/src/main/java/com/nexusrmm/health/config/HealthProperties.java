package com.nexusrmm.health.config;

import com.nexusrmm.health.model.ComponentId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Binds {@code nexus.health.*} (see application.yml). A constructor-bound
 * record, kept separate from the existing NexusProperties so Module 1's
 * configuration class is untouched; Spring ignores the unknown {@code health}
 * key when binding NexusProperties. Every value has a default equal to the
 * shipped application.yml, and {@link #toSettings()} converts into the
 * framework-free {@link HealthSettings} the engines use (which also
 * validates the combination, failing startup on e.g. warn >= critical).
 * Weight maps are merged over the defaults, so overriding one component's
 * weight does not zero the others.
 */
@ConfigurationProperties(prefix = "nexus.health")
public record HealthProperties(
        @DefaultValue("10") int cacheTtlSeconds,
        @DefaultValue("12") int minSamples,
        @DefaultValue Windows windows,
        @DefaultValue Trend trend,
        @DefaultValue Anomaly anomaly,
        @DefaultValue Thresholds thresholds,
        @DefaultValue Scoring scoring,
        Map<ComponentId, Double> weights,
        @DefaultValue Risk risk
) {

    public record Windows(@DefaultValue("PT1H") Duration shortWindow,
                          @DefaultValue("P7D") Duration longWindow,
                          @DefaultValue("PT30M") Duration bucket,
                          @DefaultValue("P7D") Duration eventWindow,
                          @DefaultValue("20000") int maxRawSamples,
                          @DefaultValue("1000") int maxEvents) {
    }

    public record Trend(@DefaultValue("24") int minBuckets,
                        @DefaultValue("PT24H") Duration minSpan,
                        @DefaultValue("0.5") double minRSquared,
                        @DefaultValue("0.25") double minDensity) {
    }

    public record Anomaly(@DefaultValue("3.5") double zThreshold,
                          @DefaultValue("48") int minBaselineBuckets) {
    }

    public record Thresholds(@DefaultValue("75") double cpuWarn, @DefaultValue("90") double cpuCritical,
                             @DefaultValue("80") double memoryWarn, @DefaultValue("92") double memoryCritical,
                             @DefaultValue("85") double storageWarn, @DefaultValue("95") double storageCritical,
                             @DefaultValue("80") double temperatureWarn, @DefaultValue("90") double temperatureCritical,
                             @DefaultValue("20") double batteryLow, @DefaultValue("10") double batteryCritical) {
    }

    public record Scoring(@DefaultValue("85") int healthyMin, @DefaultValue("65") int fairMin,
                          @DefaultValue("40") int degradedMin, @DefaultValue("30") int worstComponentMargin) {
    }

    public record Risk(@DefaultValue("20") int lowMax, @DefaultValue("45") int moderateMax, @DefaultValue("70") int highMax,
                       @DefaultValue("30") double storageHorizonWarnDays,
                       @DefaultValue("7") double storageHorizonCriticalDays,
                       Map<ComponentId, Double> weights) {
    }

    public HealthSettings toSettings(Duration collectionInterval) {
        HealthSettings d = HealthSettings.defaults();

        Map<ComponentId, Double> w = new EnumMap<>(ComponentId.class);
        w.putAll(d.weights());
        if (weights != null) w.putAll(weights);

        Map<ComponentId, Double> rw = new EnumMap<>(ComponentId.class);
        rw.putAll(d.risk().weights());
        if (risk.weights() != null) rw.putAll(risk.weights());

        return new HealthSettings(
                collectionInterval,
                Duration.ofSeconds(cacheTtlSeconds),
                new HealthSettings.Windows(windows.shortWindow(), windows.longWindow(), windows.bucket(), windows.eventWindow(),
                        windows.maxRawSamples(), windows.maxEvents()),
                minSamples,
                new HealthSettings.Trend(trend.minBuckets(), trend.minSpan(), trend.minRSquared(), trend.minDensity()),
                new HealthSettings.Anomaly(anomaly.zThreshold(), anomaly.minBaselineBuckets()),
                new HealthSettings.Thresholds(thresholds.cpuWarn(), thresholds.cpuCritical(), thresholds.memoryWarn(),
                        thresholds.memoryCritical(), thresholds.storageWarn(), thresholds.storageCritical(),
                        thresholds.temperatureWarn(), thresholds.temperatureCritical(), thresholds.batteryLow(),
                        thresholds.batteryCritical()),
                new HealthSettings.Scoring(scoring.healthyMin(), scoring.fairMin(), scoring.degradedMin(), scoring.worstComponentMargin()),
                w,
                new HealthSettings.Risk(risk.lowMax(), risk.moderateMax(), risk.highMax(), risk.storageHorizonWarnDays(),
                        risk.storageHorizonCriticalDays(), rw)
        );
    }
}
