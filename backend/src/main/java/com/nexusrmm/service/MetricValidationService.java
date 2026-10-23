package com.nexusrmm.service;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.provider.AvailabilityStatus;
import com.nexusrmm.provider.MetricReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;

/**
 * Guards against storing a value a provider claims is real but that is
 * physically implausible — a negative percentage, a counter wraparound
 * spike, a temperature reading outside any real CPU/GPU's operating range.
 *
 * This does not decide anything about health or risk (that is Module 2's
 * job); it only prevents garbage from ever entering the historical record
 * as if it were trustworthy telemetry.
 */
@Service
public class MetricValidationService {

    private static final Logger log = LoggerFactory.getLogger(MetricValidationService.class);

    private record Range(double min, double max) {
    }

    private static final Map<MetricType, Range> BOUNDS = new EnumMap<>(MetricType.class);

    static {
        BOUNDS.put(MetricType.CPU_UTILIZATION, new Range(0, 100));
        BOUNDS.put(MetricType.RAM_UTILIZATION, new Range(0, 100));
        BOUNDS.put(MetricType.STORAGE_UTILIZATION, new Range(0, 100));
        BOUNDS.put(MetricType.GPU_UTILIZATION, new Range(0, 100));
        BOUNDS.put(MetricType.BATTERY_LEVEL, new Range(0, 100));
        // Realistic operating envelope; outside this a sensor is almost
        // certainly misreporting rather than describing real hardware.
        BOUNDS.put(MetricType.CPU_TEMPERATURE, new Range(-40, 130));
        BOUNDS.put(MetricType.GPU_TEMPERATURE, new Range(-40, 130));
        // Byte rates: must be non-negative; ceiling catches 64-bit counter
        // wraparound artifacts rather than any real sustained throughput.
        BOUNDS.put(MetricType.STORAGE_READ_BYTES_PER_SEC, new Range(0, 20_000_000_000.0));
        BOUNDS.put(MetricType.STORAGE_WRITE_BYTES_PER_SEC, new Range(0, 20_000_000_000.0));
        BOUNDS.put(MetricType.NETWORK_RX_BYTES_PER_SEC, new Range(0, 20_000_000_000.0));
        BOUNDS.put(MetricType.NETWORK_TX_BYTES_PER_SEC, new Range(0, 20_000_000_000.0));
    }

    /**
     * Returns the reading unchanged if it passes validation, or a
     * replacement ERROR reading (value discarded) if it does not.
     * Readings that are already non-AVAILABLE pass through untouched —
     * there is nothing to validate about an honestly-absent value.
     */
    public MetricReading validate(MetricReading reading) {
        if (reading.availabilityStatus() != AvailabilityStatus.AVAILABLE || reading.value() == null) {
            return reading;
        }

        Range range = BOUNDS.get(reading.metricType());
        if (range == null) {
            return reading; // no declared bounds for this type; nothing to check
        }

        double v = reading.value();
        if (Double.isNaN(v) || Double.isInfinite(v) || v < range.min() || v > range.max()) {
            String diagnostic = "Discarded implausible reading (%.2f), expected range [%.0f, %.0f]."
                    .formatted(v, range.min(), range.max());
            log.warn("Validation rejected {} [{}]: {}", reading.metricType(), reading.instanceLabel(), diagnostic);
            return MetricReading.error(reading.metricType(), reading.instanceLabel(), reading.source(), diagnostic);
        }

        return reading;
    }
}
