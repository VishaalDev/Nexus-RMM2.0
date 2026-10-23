package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;

/**
 * Result of one collected reading for one metric, for one hardware
 * instance, on one poll cycle.
 *
 * instanceLabel identifies which physical/logical instance this reading
 * belongs to when a provider covers more than one — e.g. "C:", "PhysicalDrive0",
 * "Realtek PCIe GbE", "GPU0". It is null for metrics that only ever have a
 * single instance on a machine (CPU utilization, RAM utilization).
 *
 * value is null whenever availabilityStatus is anything other than
 * AVAILABLE — callers must never substitute a fake number in that case.
 */
public record MetricReading(
        MetricType metricType,
        String instanceLabel,
        Double value,
        String unit,
        AvailabilityStatus availabilityStatus,
        String source,
        String diagnostic
) {
    public static MetricReading unavailable(MetricType type, String instanceLabel, String source, String diagnostic) {
        return new MetricReading(type, instanceLabel, null, null, AvailabilityStatus.UNAVAILABLE, source, diagnostic);
    }

    public static MetricReading notApplicable(MetricType type, String instanceLabel, String source, String diagnostic) {
        return new MetricReading(type, instanceLabel, null, null, AvailabilityStatus.NOT_APPLICABLE, source, diagnostic);
    }

    public static MetricReading unsupported(MetricType type, String instanceLabel, String source, String diagnostic) {
        return new MetricReading(type, instanceLabel, null, null, AvailabilityStatus.UNSUPPORTED, source, diagnostic);
    }

    public static MetricReading error(MetricType type, String instanceLabel, String source, String diagnostic) {
        return new MetricReading(type, instanceLabel, null, null, AvailabilityStatus.ERROR, source, diagnostic);
    }

    public static MetricReading available(MetricType type, String instanceLabel, double value, String unit, String source) {
        return new MetricReading(type, instanceLabel, value, unit, AvailabilityStatus.AVAILABLE, source, null);
    }
}
