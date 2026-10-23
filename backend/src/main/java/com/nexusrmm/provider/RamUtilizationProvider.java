package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Real system RAM utilization via OSHI's GlobalMemory. Unlike CPU, memory
 * usage has no tick-delta warm-up — every poll is a direct, immediately
 * valid reading.
 */
@Component
public class RamUtilizationProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(RamUtilizationProvider.class);
    private static final String SOURCE = "OSHI/GlobalMemory";

    private final GlobalMemory memory;
    private volatile String lastDiagnostic = "No sample collected yet.";

    public RamUtilizationProvider(HardwareAbstractionLayer hal) {
        this.memory = hal.getMemory();
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.RAM_UTILIZATION);
    }

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public boolean available() {
        return memory != null && memory.getTotal() > 0;
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
        try {
            long total = memory.getTotal();
            if (total <= 0) {
                lastDiagnostic = "Platform did not report total physical memory.";
                return List.of(MetricReading.unavailable(MetricType.RAM_UTILIZATION, null, SOURCE, lastDiagnostic));
            }

            long available = memory.getAvailable();
            double usedPercent = (1.0 - ((double) available / (double) total)) * 100.0;
            double clamped = Math.max(0.0, Math.min(100.0, usedPercent));
            lastDiagnostic = "OK";

            return List.of(MetricReading.available(MetricType.RAM_UTILIZATION, null, round(clamped), "%", SOURCE));
        } catch (Exception e) {
            log.warn("RAM utilization collection failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(MetricReading.error(MetricType.RAM_UTILIZATION, null, SOURCE, lastDiagnostic));
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
