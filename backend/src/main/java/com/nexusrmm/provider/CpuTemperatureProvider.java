package com.nexusrmm.provider;

import com.nexusrmm.config.NexusProperties;
import com.nexusrmm.entity.MetricType;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.Sensors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * CPU package temperature. Primary path is OSHI's cross-platform Sensors
 * API; on many Windows machines this returns 0 (OSHI's "not available"
 * convention) because Windows does not expose a standard thermal sensor
 * API without a vendor driver. When that happens, this provider reports an
 * honest UNAVAILABLE reading and folds in a real PawnIO driver-presence
 * check (see {@link PawnIoDriverDetector}) so the diagnostic tells the
 * operator specifically what is and isn't installed — never a fabricated
 * temperature.
 */
@Component
public class CpuTemperatureProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(CpuTemperatureProvider.class);
    private static final String SOURCE = "OSHI/Sensors";

    private final Sensors sensors;
    private final PawnIoDriverDetector pawnIoDriverDetector;
    private final NexusProperties properties;
    private volatile String lastDiagnostic = "No sample collected yet.";

    public CpuTemperatureProvider(HardwareAbstractionLayer hal,
                                   PawnIoDriverDetector pawnIoDriverDetector,
                                   NexusProperties properties) {
        this.sensors = hal.getSensors();
        this.pawnIoDriverDetector = pawnIoDriverDetector;
        this.properties = properties;
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.CPU_TEMPERATURE);
    }

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public boolean available() {
        try {
            return sensors.getCpuTemperature() > 0;
        } catch (Exception e) {
            return false;
        }
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
            double temp = sensors.getCpuTemperature();

            if (temp > 0) {
                lastDiagnostic = "OK";
                return List.of(MetricReading.available(MetricType.CPU_TEMPERATURE, null, round(temp), "\u00B0C", SOURCE));
            }

            lastDiagnostic = buildUnavailableDiagnostic();
            return List.of(MetricReading.unavailable(MetricType.CPU_TEMPERATURE, null, SOURCE, lastDiagnostic));
        } catch (Exception e) {
            log.warn("CPU temperature collection failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(MetricReading.error(MetricType.CPU_TEMPERATURE, null, SOURCE, lastDiagnostic));
        }
    }

    private String buildUnavailableDiagnostic() {
        StringBuilder sb = new StringBuilder(
                "No supported thermal sensor was detected via the platform sensor API.");

        PawnIoDriverDetector.DriverStatus status = pawnIoDriverDetector.detect();
        if (!status.applicable()) {
            sb.append(' ').append(status.detail());
        } else if (!status.installed()) {
            sb.append(' ').append("PawnIO driver is not installed, so MSR-based thermal reporting is not possible on this run.");
        } else {
            String modulePath = properties.getHardware().getPawnio().getModulePath();
            if (modulePath == null || modulePath.isBlank()) {
                sb.append(' ').append("PawnIO driver is present, but no MSR module path is configured (nexus.hardware.pawnio.module-path) "
                        + "and this build does not yet implement the PawnIO MSR execution protocol.");
            } else if (!new File(modulePath).exists()) {
                sb.append(' ').append("PawnIO driver is present, but the configured module path does not exist: " + modulePath + ".");
            } else {
                sb.append(' ').append("PawnIO driver and MSR module are present, but this build does not yet implement "
                        + "MSR execution — see docs/hardware-monitoring.md.");
            }
        }
        return sb.toString();
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
