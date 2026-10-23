package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PowerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Real battery charge level via OSHI's PowerSource API. A desktop with no
 * battery hardware is not an error condition — it correctly reports
 * NOT_APPLICABLE rather than UNAVAILABLE, per the project's capability
 * model (a missing battery is a fact about the machine, not a sensor
 * failure).
 */
@Component
public class BatteryLevelProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(BatteryLevelProvider.class);
    private static final String SOURCE = "OSHI/PowerSource";

    private final HardwareAbstractionLayer hal;
    private volatile String lastDiagnostic = "No sample collected yet.";

    public BatteryLevelProvider(HardwareAbstractionLayer hal) {
        this.hal = hal;
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.BATTERY_LEVEL);
    }

    @Override
    public boolean supported() {
        return true; // supportable on any platform; whether *this* machine has one is checked per-cycle
    }

    @Override
    public boolean available() {
        try {
            return !hal.getPowerSources().isEmpty();
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
        List<MetricReading> readings = new ArrayList<>();
        try {
            List<PowerSource> sources = hal.getPowerSources();

            if (sources.isEmpty()) {
                lastDiagnostic = "No battery hardware detected; this appears to be a desktop or the platform does not expose a power source.";
                return List.of(MetricReading.notApplicable(MetricType.BATTERY_LEVEL, null, SOURCE, lastDiagnostic));
            }

            for (PowerSource ps : sources) {
                String label = ps.getName() == null || ps.getName().isBlank() ? "Battery" : ps.getName();
                double percent = ps.getRemainingCapacityPercent() * 100.0;

                if (Double.isNaN(percent) || percent < 0) {
                    readings.add(MetricReading.unavailable(MetricType.BATTERY_LEVEL, label, SOURCE,
                            "Power source detected but did not report a valid capacity percentage."));
                    continue;
                }

                double clamped = Math.max(0.0, Math.min(100.0, percent));
                readings.add(MetricReading.available(MetricType.BATTERY_LEVEL, label, round(clamped), "%", SOURCE));
            }

            lastDiagnostic = "OK";
            return readings;
        } catch (Exception e) {
            log.warn("Battery level collection failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(MetricReading.error(MetricType.BATTERY_LEVEL, null, SOURCE, lastDiagnostic));
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
