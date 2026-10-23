package com.nexusrmm.health.analysis;

import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.provider.HealthDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs every {@link ComponentAnalyzer} against one fixed "now" and folds
 * the results into a {@link HealthReport}.
 *
 * Overall score: weighted mean of the components that HAVE a score
 * (NO_DATA components are excluded, never counted as 0 or 100), then
 * capped at (lowest component score + worst-component-margin) so a single
 * failing component cannot be averaged away by healthy ones. The report's
 * dataCoverage is the share of total weight that could be scored, so a
 * high overall score computed from little data is visibly low-coverage.
 *
 * Failure isolation: an analyzer that throws yields a NO_DATA component
 * naming the failure; the other seven still report.
 */
public final class HealthAnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(HealthAnalysisEngine.class);

    private static final List<String> STATIC_LIMITATIONS = List.of(
            "GPU health is not assessed: Module 1 reports GPU utilization and temperature as UNSUPPORTED (no vendor SDK).",
            "Drive health (SMART/NVMe wear, reallocated sectors) is not collected; storage scores reflect capacity and activity only.",
            "Battery wear, design capacity and cycle count are not collected; battery scores reflect charge-level usage only.",
            "Scores describe observed telemetry only. They are heuristics, not a diagnosis, and were not calibrated against failure outcomes."
    );

    private final List<ComponentAnalyzer> analyzers;
    private final HealthSettings settings;

    public HealthAnalysisEngine(List<ComponentAnalyzer> analyzers, HealthSettings settings) {
        this.analyzers = List.copyOf(analyzers);
        this.settings = settings;
    }

    /** The eight production analyzers, one per {@link ComponentId}. */
    public static List<ComponentAnalyzer> defaultAnalyzers() {
        return List.of(new CpuAnalyzer(), new MemoryAnalyzer(), new StorageCapacityAnalyzer(), new StorageIoAnalyzer(),
                new ThermalAnalyzer(), new BatteryAnalyzer(), new NetworkAnalyzer(), new ReliabilityAnalyzer());
    }

    public HealthReport analyze(HealthDataProvider data, Instant now) {
        AnalysisContext ctx = new AnalysisContext(data, settings, now);

        List<ComponentHealth> results = new ArrayList<>();
        for (ComponentAnalyzer analyzer : analyzers) {
            try {
                results.add(analyzer.analyze(ctx));
            } catch (RuntimeException e) {
                log.warn("Analyzer {} failed", analyzer.component(), e);
                results.add(ComponentHealth.noData(analyzer.component(), settings.weight(analyzer.component()),
                        "Analyzer failed before producing a result.",
                        "Analysis failed unexpectedly (" + e.getClass().getSimpleName() + "); see the backend log.", List.of()));
            }
        }
        results.sort(Comparator.comparing(ComponentHealth::component));

        double totalWeight = results.stream().mapToDouble(ComponentHealth::weight).sum();
        List<ComponentHealth> scored = results.stream().filter(ComponentHealth::hasScore).toList();
        double scoredWeight = scored.stream().mapToDouble(ComponentHealth::weight).sum();
        double coverage = totalWeight <= 0 ? 0 : AnalyzerSupport.round(scoredWeight / totalWeight, 3);

        Integer overall = null;
        HealthStatus status = HealthStatus.NO_DATA;
        String basis;
        if (scored.isEmpty() || scoredWeight <= 0) {
            basis = "No component had enough data to be scored.";
        } else {
            double mean = scored.stream().mapToDouble(c -> c.score() * c.weight()).sum() / scoredWeight;
            int worst = scored.stream().mapToInt(ComponentHealth::score).min().orElse(100);
            double capped = Math.min(mean, worst + settings.scoring().worstComponentMargin());
            overall = (int) Math.round(capped);
            status = Scoring.statusFor(overall, settings);
            basis = "Weighted mean of " + scored.size() + " of " + results.size() + " components (" + Math.round(coverage * 100)
                    + "% of total weight had data), capped at lowest component score + " + settings.scoring().worstComponentMargin() + ".";
        }

        List<String> limitations = new ArrayList<>(STATIC_LIMITATIONS);
        for (ComponentHealth c : results) {
            if (!c.hasScore()) {
                limitations.add(c.label() + " not scored: " + c.noDataReason());
            }
        }

        return new HealthReport(now, data.computerId(), overall, status, coverage, basis, results, limitations);
    }
}
