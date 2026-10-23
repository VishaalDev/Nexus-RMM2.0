package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * GPU utilization and temperature genuinely cannot be read through OSHI —
 * it exposes static GPU inventory (name, vendor, VRAM) but not real-time
 * counters, which require a vendor SDK (NVIDIA NVML, AMD ADL, Intel's
 * equivalent) that this build does not integrate. Rather than skip GPUs
 * silently or fabricate a number, this provider enumerates every real GPU
 * it finds and reports each one's utilization/temperature as UNSUPPORTED
 * with a specific diagnostic — exactly the capability-reporting pattern
 * the project requires ("do not fake unsupported sensors").
 */
@Component
public class GpuMetricsProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(GpuMetricsProvider.class);
    private static final String SOURCE = "OSHI/GraphicsCard";
    private static final String VENDOR_SDK_DIAGNOSTIC =
            "OSHI does not expose real-time GPU utilization/temperature counters; this requires a vendor SDK "
            + "(e.g. NVIDIA NVML, AMD ADL) that is not yet integrated. GPU inventory (name, vendor, VRAM) is "
            + "still available via /api/device/hardware.";

    private final HardwareAbstractionLayer hal;
    private volatile String lastDiagnostic = "No sample collected yet.";

    public GpuMetricsProvider(HardwareAbstractionLayer hal) {
        this.hal = hal;
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.GPU_UTILIZATION, MetricType.GPU_TEMPERATURE);
    }

    @Override
    public boolean supported() {
        // The *sensor category* is unsupported in this build regardless of
        // hardware present — no vendor SDK is integrated yet.
        return false;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public String diagnostic() {
        return lastDiagnostic;
    }

    @Override
    public List<MetricReading> collect() {
        List<MetricReading> readings = new ArrayList<>();
        try {
            List<GraphicsCard> cards = hal.getGraphicsCards();

            if (cards.isEmpty()) {
                lastDiagnostic = "No display adapters were detected by the platform.";
                readings.add(MetricReading.unsupported(MetricType.GPU_UTILIZATION, null, SOURCE, lastDiagnostic));
                readings.add(MetricReading.unsupported(MetricType.GPU_TEMPERATURE, null, SOURCE, lastDiagnostic));
                return readings;
            }

            for (GraphicsCard card : cards) {
                String label = card.getName() == null || card.getName().isBlank() ? "GPU" : card.getName();
                readings.add(MetricReading.unsupported(MetricType.GPU_UTILIZATION, label, SOURCE, VENDOR_SDK_DIAGNOSTIC));
                readings.add(MetricReading.unsupported(MetricType.GPU_TEMPERATURE, label, SOURCE, VENDOR_SDK_DIAGNOSTIC));
            }

            lastDiagnostic = VENDOR_SDK_DIAGNOSTIC;
            return readings;
        } catch (Exception e) {
            log.warn("GPU enumeration failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(
                    MetricReading.error(MetricType.GPU_UTILIZATION, null, SOURCE, lastDiagnostic),
                    MetricReading.error(MetricType.GPU_TEMPERATURE, null, SOURCE, lastDiagnostic)
            );
        }
    }
}
