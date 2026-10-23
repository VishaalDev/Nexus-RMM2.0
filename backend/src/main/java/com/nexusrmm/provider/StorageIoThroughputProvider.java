package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real per-physical-disk I/O throughput, derived from OSHI's cumulative
 * read/write byte counters the same way {@link CpuUtilizationProvider}
 * derives CPU load: keep the previous counter snapshot per disk and divide
 * the delta by the real elapsed wall-clock time since that snapshot. The
 * first cycle for each disk has nothing to diff against and is reported as
 * an honest baseline-establishing UNAVAILABLE sample, not a fabricated 0.
 */
@Component
public class StorageIoThroughputProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(StorageIoThroughputProvider.class);
    private static final String SOURCE = "OSHI/HWDiskStore";

    private record Snapshot(long readBytes, long writeBytes, long atNanos) {
    }

    private final HardwareAbstractionLayer hal;
    private final Map<String, Snapshot> previous = new ConcurrentHashMap<>();
    private volatile String lastDiagnostic = "No sample collected yet.";

    public StorageIoThroughputProvider(HardwareAbstractionLayer hal) {
        this.hal = hal;
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.STORAGE_READ_BYTES_PER_SEC, MetricType.STORAGE_WRITE_BYTES_PER_SEC);
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
            List<HWDiskStore> disks = hal.getDiskStores();
            if (disks.isEmpty()) {
                lastDiagnostic = "No physical disks were reported by the platform.";
                return List.of(
                        MetricReading.unavailable(MetricType.STORAGE_READ_BYTES_PER_SEC, null, SOURCE, lastDiagnostic),
                        MetricReading.unavailable(MetricType.STORAGE_WRITE_BYTES_PER_SEC, null, SOURCE, lastDiagnostic)
                );
            }

            long now = System.nanoTime();
            for (HWDiskStore disk : disks) {
                String label = disk.getName();
                long readBytes = disk.getReadBytes();
                long writeBytes = disk.getWriteBytes();

                Snapshot prior = previous.put(label, new Snapshot(readBytes, writeBytes, now));

                if (prior == null) {
                    String diag = "Establishing I/O baseline for " + label + "; throughput available on the next collection cycle.";
                    readings.add(MetricReading.unavailable(MetricType.STORAGE_READ_BYTES_PER_SEC, label, SOURCE, diag));
                    readings.add(MetricReading.unavailable(MetricType.STORAGE_WRITE_BYTES_PER_SEC, label, SOURCE, diag));
                    continue;
                }

                double elapsedSeconds = (now - prior.atNanos()) / 1_000_000_000.0;
                long readDelta = readBytes - prior.readBytes();
                long writeDelta = writeBytes - prior.writeBytes();

                if (elapsedSeconds <= 0 || readDelta < 0 || writeDelta < 0) {
                    // Counter reset (device replaced/reconnected) or clock anomaly;
                    // re-baseline rather than report a nonsensical negative rate.
                    String diag = "I/O counters reset for " + label + "; re-establishing baseline.";
                    readings.add(MetricReading.unavailable(MetricType.STORAGE_READ_BYTES_PER_SEC, label, SOURCE, diag));
                    readings.add(MetricReading.unavailable(MetricType.STORAGE_WRITE_BYTES_PER_SEC, label, SOURCE, diag));
                    continue;
                }

                double readRate = readDelta / elapsedSeconds;
                double writeRate = writeDelta / elapsedSeconds;

                readings.add(MetricReading.available(MetricType.STORAGE_READ_BYTES_PER_SEC, label, round(readRate), "B/s", SOURCE));
                readings.add(MetricReading.available(MetricType.STORAGE_WRITE_BYTES_PER_SEC, label, round(writeRate), "B/s", SOURCE));
            }

            lastDiagnostic = "OK";
            return readings;
        } catch (Exception e) {
            log.warn("Storage I/O throughput collection failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(
                    MetricReading.error(MetricType.STORAGE_READ_BYTES_PER_SEC, null, SOURCE, lastDiagnostic),
                    MetricReading.error(MetricType.STORAGE_WRITE_BYTES_PER_SEC, null, SOURCE, lastDiagnostic)
            );
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
