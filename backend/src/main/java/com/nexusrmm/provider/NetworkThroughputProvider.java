package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real per-network-adapter throughput, one reading pair per adapter that
 * is currently operationally up. Same cumulative-counter-delta approach as
 * {@link StorageIoThroughputProvider} — adapters that are down/disconnected
 * are simply not reported this cycle rather than shown as 0 throughput,
 * since "0 B/s" and "not connected" are different facts.
 */
@Component
public class NetworkThroughputProvider implements MetricProvider {

    private static final Logger log = LoggerFactory.getLogger(NetworkThroughputProvider.class);
    private static final String SOURCE = "OSHI/NetworkIF";

    private record Snapshot(long recvBytes, long sentBytes, long atNanos) {
    }

    private final HardwareAbstractionLayer hal;
    private final Map<String, Snapshot> previous = new ConcurrentHashMap<>();
    private volatile String lastDiagnostic = "No sample collected yet.";

    public NetworkThroughputProvider(HardwareAbstractionLayer hal) {
        this.hal = hal;
    }

    @Override
    public Set<MetricType> providedMetricTypes() {
        return Set.of(MetricType.NETWORK_RX_BYTES_PER_SEC, MetricType.NETWORK_TX_BYTES_PER_SEC);
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
            List<NetworkIF> allInterfaces = hal.getNetworkIFs();
            List<NetworkIF> active = allInterfaces.stream()
                    .filter(nif -> nif.getIfOperStatus() == NetworkIF.IfOperStatus.UP)
                    .toList();

            if (active.isEmpty()) {
                lastDiagnostic = allInterfaces.isEmpty()
                        ? "No network adapters were reported by the platform."
                        : "No network adapters are currently operationally up.";
                return List.of(
                        MetricReading.unavailable(MetricType.NETWORK_RX_BYTES_PER_SEC, null, SOURCE, lastDiagnostic),
                        MetricReading.unavailable(MetricType.NETWORK_TX_BYTES_PER_SEC, null, SOURCE, lastDiagnostic)
                );
            }

            long now = System.nanoTime();
            for (NetworkIF nif : active) {
                String label = !nif.getDisplayName().isBlank() ? nif.getDisplayName() : nif.getName();
                long recv = nif.getBytesRecv();
                long sent = nif.getBytesSent();

                Snapshot prior = previous.put(label, new Snapshot(recv, sent, now));

                if (prior == null) {
                    String diag = "Establishing throughput baseline for " + label + "; available on the next collection cycle.";
                    readings.add(MetricReading.unavailable(MetricType.NETWORK_RX_BYTES_PER_SEC, label, SOURCE, diag));
                    readings.add(MetricReading.unavailable(MetricType.NETWORK_TX_BYTES_PER_SEC, label, SOURCE, diag));
                    continue;
                }

                double elapsedSeconds = (now - prior.atNanos()) / 1_000_000_000.0;
                long recvDelta = recv - prior.recvBytes();
                long sentDelta = sent - prior.sentBytes();

                if (elapsedSeconds <= 0 || recvDelta < 0 || sentDelta < 0) {
                    String diag = "Counters reset for " + label + "; re-establishing baseline.";
                    readings.add(MetricReading.unavailable(MetricType.NETWORK_RX_BYTES_PER_SEC, label, SOURCE, diag));
                    readings.add(MetricReading.unavailable(MetricType.NETWORK_TX_BYTES_PER_SEC, label, SOURCE, diag));
                    continue;
                }

                readings.add(MetricReading.available(MetricType.NETWORK_RX_BYTES_PER_SEC, label,
                        round(recvDelta / elapsedSeconds), "B/s", SOURCE));
                readings.add(MetricReading.available(MetricType.NETWORK_TX_BYTES_PER_SEC, label,
                        round(sentDelta / elapsedSeconds), "B/s", SOURCE));
            }

            lastDiagnostic = "OK";
            return readings;
        } catch (Exception e) {
            log.warn("Network throughput collection failed", e);
            lastDiagnostic = "Collection error: " + e.getMessage();
            return List.of(
                    MetricReading.error(MetricType.NETWORK_RX_BYTES_PER_SEC, null, SOURCE, lastDiagnostic),
                    MetricReading.error(MetricType.NETWORK_TX_BYTES_PER_SEC, null, SOURCE, lastDiagnostic)
            );
        }
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
