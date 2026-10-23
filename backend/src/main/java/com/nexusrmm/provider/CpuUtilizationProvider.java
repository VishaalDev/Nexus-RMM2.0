package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;
import oshi.hardware.CentralProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real system-wide CPU utilization via OSHI's tick-delta API.
 *
 * OSHI computes load as the delta between two tick snapshots, so this
 * provider keeps the previous snapshot between poll cycles (~10s apart by
 * default) instead of blocking the collection thread with an internal
 * sleep. The very first sample after startup has no prior snapshot to
 * diff against, so it is honestly reported as UNAVAILABLE rather than
 * guessed.
 */
@Component
public class CpuUtilizationProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(CpuUtilizationProvider.class);
    private static final String SOURCE = "OSHI/CentralProcessor";

    private final CentralProcessor processor;
    private final AtomicReference<long[]> previousTicks = new AtomicReference<>();
    private volatile String lastDiagnostic = "No sample collected yet.";

    public CpuUtilizationProvider(oshi.hardware.HardwareAbstractionLayer hal) {
        this.processor = hal.getProcessor();
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.CPU_UTILIZATION);
    }

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public boolean available() {
        return processor != null;
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
            long[] currentTicks = processor.getSystemCpuLoadTicks();
            long[] prior = previousTicks.getAndSet(currentTicks);

            if (prior == null) {
                lastDiagnostic = "Establishing CPU load baseline; utilization will be available on the next collection cycle.";
                return List.of(MetricReading.unavailable(MetricType.CPU_UTILIZATION, null, SOURCE, lastDiagnostic));
            }

            double load = processor.getSystemCpuLoadBetweenTicks(prior) * 100.0;
            double clamped = Math.max(0.0, Math.min(100.0, load));
            lastDiagnostic = "OK";

            return List.of(MetricReading.available(MetricType.CPU_UTILIZATION, null, round(clamped), "%", SOURCE));
        } catch (Exception e) {
            log.warn("CPU utilization collection failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(MetricReading.error(MetricType.CPU_UTILIZATION, null, SOURCE, lastDiagnostic));
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
