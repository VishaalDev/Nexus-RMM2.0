package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;
import oshi.software.os.FileSystem;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Real per-volume storage capacity/utilization via OSHI's FileSystem API.
 * Multi-instance: one reading per mounted volume (drive letter on Windows),
 * labeled by its mount point. Pseudo filesystems with no real capacity are
 * skipped rather than reported as 0% used.
 */
@Component
public class StorageUtilizationProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(StorageUtilizationProvider.class);
    private static final String SOURCE = "OSHI/FileSystem";

    private final OperatingSystem operatingSystem;
    private volatile String lastDiagnostic = "No sample collected yet.";

    public StorageUtilizationProvider(OperatingSystem operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.STORAGE_UTILIZATION);
    }

    @Override
    public boolean supported() {
        return true;
    }

    @Override
    public boolean available() {
        return true;
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
            FileSystem fs = operatingSystem.getFileSystem();
            List<OSFileStore> stores = fs.getFileStores(true);

            if (stores.isEmpty()) {
                lastDiagnostic = "No mounted volumes were reported by the platform.";
                return List.of(MetricReading.unavailable(MetricType.STORAGE_UTILIZATION, null, SOURCE, lastDiagnostic));
            }

            for (OSFileStore store : stores) {
                long total = store.getTotalSpace();
                String label = !store.getMount().isBlank() ? store.getMount() : store.getName();

                if (total <= 0) {
                    // Pseudo/virtual filesystem with no real capacity to report.
                    continue;
                }

                long usable = store.getUsableSpace();
                double usedPercent = (1.0 - ((double) usable / (double) total)) * 100.0;
                double clamped = Math.max(0.0, Math.min(100.0, usedPercent));

                readings.add(MetricReading.available(MetricType.STORAGE_UTILIZATION, label, round(clamped), "%", SOURCE));
            }

            lastDiagnostic = readings.isEmpty()
                    ? "All reported volumes had zero reported capacity."
                    : "OK";

            if (readings.isEmpty()) {
                return List.of(MetricReading.unavailable(MetricType.STORAGE_UTILIZATION, null, SOURCE, lastDiagnostic));
            }
            return readings;
        } catch (Exception e) {
            log.warn("Storage utilization collection failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(MetricReading.error(MetricType.STORAGE_UTILIZATION, null, SOURCE, lastDiagnostic));
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
