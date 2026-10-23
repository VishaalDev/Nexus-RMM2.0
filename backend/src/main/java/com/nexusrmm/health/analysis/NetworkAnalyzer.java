package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.stats.Curves;
import com.nexusrmm.provider.AvailabilityStatus;

import java.util.ArrayList;
import java.util.List;

import static com.nexusrmm.health.analysis.AnalyzerSupport.ev;
import static com.nexusrmm.health.analysis.AnalyzerSupport.metrics;

/**
 * Per adapter: unusual receive/transmit activity versus this machine's own
 * baseline, plus a connectivity check. Module 1 collects throughput only -
 * no packet loss, errors, latency or link quality - so those cannot be
 * assessed and the basis says so. Unusually HIGH activity is a weak signal
 * (max penalty 10 per direction; a big download is legitimate), unusually
 * LOW activity is informational.
 *
 * Connectivity: when no adapter reported values during the short window
 * but the collector stored at least {@code min-samples} explicit
 * UNAVAILABLE rows for "no operationally-up adapter" (which is how the
 * Module 1 provider reports that state), the machine really had no network
 * and that is scored (penalty 30). If there are simply no rows at all,
 * the collector was not running, and that is NO_DATA - never mistaken for
 * an outage or for health.
 */
public final class NetworkAnalyzer implements ComponentAnalyzer {

    private static final double MAX_PENALTY_PER_DIRECTION = 10;
    private static final double OFFLINE_PENALTY = 30;

    @Override
    public ComponentId component() {
        return ComponentId.NETWORK;
    }

    @Override
    public ComponentHealth analyze(AnalysisContext ctx) {
        String basis = "Receive/transmit throughput per adapter compared with this machine's own baseline, plus an adapter-connectivity check. "
                + "Packet loss, errors, latency and link quality are not collected.";

        List<String> adapters = AnalyzerSupport.instancesOrSingle(ctx, MetricType.NETWORK_RX_BYTES_PER_SEC, MetricType.NETWORK_TX_BYTES_PER_SEC);
        List<InstanceHealth> results = new ArrayList<>();
        for (String a : adapters) {
            if (a != null) {
                results.add(analyzeAdapter(ctx, a));
            }
        }

        boolean anyScored = results.stream().anyMatch(r -> r.score() != null);
        boolean anyActive = results.stream().anyMatch(r -> r.sampleCount() > 0);

        if (!anyActive) {
            var none = AnalyzerSupport.recent(ctx, MetricType.NETWORK_RX_BYTES_PER_SEC, null);
            long explicitDown = none.rows().stream().filter(s -> s.status() == AvailabilityStatus.UNAVAILABLE).count();
            if (explicitDown >= ctx.settings().minSamples()) {
                Finding f = Finding.warning("NETWORK_NO_ACTIVE_ADAPTER",
                        "No operationally-up network adapter reported traffic during the last "
                                + AnalyzerSupport.minutes(ctx.settings().windows().shortWindow()) + " (" + explicitDown + " samples).",
                        ev("unavailableSamples", explicitDown));
                int score = Curves.score(OFFLINE_PENALTY);
                InstanceHealth offline = new InstanceHealth(null, score, Scoring.statusFor(score, ctx.settings()),
                        (int) explicitDown, AnalyzerSupport.round(none.coverage(), 3), metrics("unavailableSamples", (double) explicitDown),
                        List.of(f), null);
                results.add(offline);
                return AnalyzerSupport.assemble(ctx, ComponentId.NETWORK, basis, results, AnalyzerSupport.Aggregation.MIN, List.of(),
                        "Network could not be assessed.");
            }
        }

        if (!anyScored) {
            String reason = results.isEmpty()
                    ? "No network adapter has reported data in the last " + ctx.settings().windows().longWindow().toDays() + " days."
                    : null;
            return AnalyzerSupport.assemble(ctx, ComponentId.NETWORK, basis, results, AnalyzerSupport.Aggregation.MIN, List.of(),
                    reason == null ? "Network could not be assessed." : reason);
        }
        return AnalyzerSupport.assemble(ctx, ComponentId.NETWORK, basis, results, AnalyzerSupport.Aggregation.MEAN, List.of(),
                "Network could not be assessed.");
    }

    private InstanceHealth analyzeAdapter(AnalysisContext ctx, String label) {
        var rx = AnalyzerSupport.recent(ctx, MetricType.NETWORK_RX_BYTES_PER_SEC, label);
        var tx = AnalyzerSupport.recent(ctx, MetricType.NETWORK_TX_BYTES_PER_SEC, label);
        int min = ctx.settings().minSamples();
        boolean rxOk = rx.valueCount() >= min;
        boolean txOk = tx.valueCount() >= min;
        int samples = rx.valueCount() + tx.valueCount();
        double coverage = Math.max(rx.coverage(), tx.coverage());

        if (!rxOk && !txOk) {
            String reason = samples == 0
                    ? "No traffic samples in the last " + AnalyzerSupport.minutes(ctx.settings().windows().shortWindow()) + " (adapter down or collector stopped)."
                    : "Insufficient data: " + Math.max(rx.valueCount(), tx.valueCount()) + " valid samples; at least " + min + " are required.";
            return InstanceHealth.noData(label, reason, samples, coverage);
        }

        var rxScan = rxOk ? AnomalySupport.scan(ctx, MetricType.NETWORK_RX_BYTES_PER_SEC, label, rx.values()) : null;
        var txScan = txOk ? AnomalySupport.scan(ctx, MetricType.NETWORK_TX_BYTES_PER_SEC, label, tx.values()) : null;
        boolean assessed = (rxScan != null && rxScan.assessed()) || (txScan != null && txScan.assessed());
        if (!assessed) {
            var s = rxScan != null ? rxScan : txScan;
            String why = s.state() == AnomalySupport.State.NO_BASELINE
                    ? "Insufficient history for a traffic baseline: " + s.baselineBuckets() + " of " + s.requiredBuckets()
                        + " required " + ctx.settings().windows().bucket().toMinutes() + "-minute buckets."
                    : "Baseline has no variation (e.g. always idle), so no anomaly can be judged.";
            return new InstanceHealth(label, null, HealthStatus.NO_DATA, samples, AnalyzerSupport.round(coverage, 3),
                    metrics("rxMeanBytesPerSec", rxScan == null ? null : rxScan.recentMean(),
                            "txMeanBytesPerSec", txScan == null ? null : txScan.recentMean()),
                    List.of(), why);
        }

        double penalty = 0;
        List<Finding> findings = new ArrayList<>();
        penalty += direction("receive", "RX", label, rxScan, ctx, findings);
        penalty += direction("transmit", "TX", label, txScan, ctx, findings);
        int score = Curves.score(penalty);
        return new InstanceHealth(label, score, Scoring.statusFor(score, ctx.settings()), samples, AnalyzerSupport.round(coverage, 3),
                metrics("rxMeanBytesPerSec", rxScan == null ? null : rxScan.recentMean(),
                        "txMeanBytesPerSec", txScan == null ? null : txScan.recentMean(),
                        "rxZ", rxScan != null && rxScan.assessed() ? rxScan.z() : null,
                        "txZ", txScan != null && txScan.assessed() ? txScan.z() : null),
                findings, null);
    }

    private double direction(String word, String code, String label, AnomalySupport.Result r, AnalysisContext ctx, List<Finding> findings) {
        if (r == null || !r.assessed()) {
            return 0;
        }
        if (r.state() == AnomalySupport.State.LOW) {
            findings.add(Finding.info("NETWORK_" + code + "_QUIET", label + " " + word + " traffic is well below its usual level ("
                    + AnomalySupport.rate(r.recentMean()) + " vs typical " + AnomalySupport.rate(r.baselineMedian()) + ").",
                    ev("instance", label, "recentMean", r.recentMean(), "baselineMedian", r.baselineMedian(), "z", r.z())));
            return 0;
        }
        if (r.state() != AnomalySupport.State.HIGH) {
            return 0;
        }
        double zt = ctx.settings().anomaly().zThreshold();
        findings.add(Finding.warning("NETWORK_" + code + "_SURGE", label + " " + word + " traffic averages "
                + AnomalySupport.rate(r.recentMean()) + " versus a typical " + AnomalySupport.rate(r.baselineMedian())
                + " (robust z = " + AnalyzerSupport.round(r.z(), 1) + ").",
                ev("instance", label, "recentMean", r.recentMean(), "baselineMedian", r.baselineMedian(), "z", r.z())));
        return Curves.ramp(r.z(), zt, zt * 3, MAX_PENALTY_PER_DIRECTION);
    }
}
