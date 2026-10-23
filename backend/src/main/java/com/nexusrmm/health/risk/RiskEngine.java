package com.nexusrmm.health.risk;

import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.ComponentRisk;
import com.nexusrmm.health.model.Confidence;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.model.RiskFactor;
import com.nexusrmm.health.model.RiskLevel;
import com.nexusrmm.health.model.RiskReport;
import com.nexusrmm.health.stats.Curves;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explainable failure-RISK index derived from a {@link HealthReport}'s
 * structured metrics. It is deliberately NOT a probability and NOT a
 * learned model: no failure labels exist to train or calibrate against.
 * Each factor has a 0..1 severity from an explicit rule; factors within a
 * component combine as 1 - prod(1 - severity) (diminishing, never above
 * 1), components combine the same way after multiplying by their risk
 * weight. Components without usable data are NOT_ASSESSED, never LOW.
 *
 * Confidence reflects evidence, not the risk itself: HIGH needs >= 7 days
 * of history and >= 60% data coverage; MEDIUM >= 24 h and >= 30%; else LOW.
 * History that is sparse (fewer than 25% of the bucket slots in its span
 * populated, e.g. a few short sessions spread over days) is always LOW.
 */
public final class RiskEngine {

    public static final String METHODOLOGY =
            "Heuristic severity index (0-100), not a probability of failure. Rule-based factors (capacity and fill-rate, "
            + "sustained heat, sustained load, event bursts and escalation) combine as 1 - prod(1 - severity). "
            + "No failure outcomes were available to calibrate it. NOT_ASSESSED means no usable evidence, not low risk.";

    private static final double SPARSE_DENSITY = 0.25;
    private static final double MIN_RISK_TREND_PER_DAY_TEMP = 0.5;
    private static final double MIN_RISK_TREND_PER_DAY_MEMORY = 1.0;

    private final HealthSettings settings;

    public RiskEngine(HealthSettings settings) {
        this.settings = settings;
    }

    public RiskReport assess(HealthReport health, Instant now) {
        List<ComponentRisk> risks = new ArrayList<>();
        for (ComponentHealth c : health.components()) {
            risks.add(assessComponent(c));
        }
        risks.sort(Comparator.comparing(ComponentRisk::component));

        double survive = 1.0;
        int assessed = 0;
        for (ComponentRisk r : risks) {
            if (r.riskScore() == null) continue;
            assessed++;
            double w = Math.min(1.0, settings.risk().weights().getOrDefault(r.component(), 0.0));
            survive *= 1.0 - w * (r.riskScore() / 100.0);
        }

        Integer overall = null;
        RiskLevel level = RiskLevel.NOT_ASSESSED;
        if (assessed > 0) {
            overall = (int) Math.round(100.0 * (1.0 - survive));
            level = levelFor(overall);
        }
        return new RiskReport(now, overall, level, overallConfidence(health), risks, METHODOLOGY);
    }

    private ComponentRisk assessComponent(ComponentHealth c) {
        ComponentId id = c.component();
        if (id == ComponentId.STORAGE_IO || id == ComponentId.NETWORK) {
            return notAssessed(c, "Activity anomalies are not failure indicators; no failure risk is derived from this component.");
        }
        if (!c.hasScore()) {
            return notAssessed(c, c.noDataReason());
        }

        List<RiskFactor> factors = new ArrayList<>();
        Double[] horizon = { null };

        switch (id) {
            case STORAGE_CAPACITY -> storage(c, factors, horizon);
            case THERMAL -> thermal(c, factors);
            case CPU -> cpu(c, factors);
            case MEMORY -> memory(c, factors);
            case BATTERY -> battery(c, factors);
            case RELIABILITY -> reliability(c, factors);
            default -> { }
        }

        double survive = 1.0;
        for (RiskFactor f : factors) {
            survive *= 1.0 - clamp01(f.severity());
        }
        int score = (int) Math.round(100.0 * (1.0 - survive));
        factors.sort(Comparator.comparingDouble(RiskFactor::severity).reversed());
        return new ComponentRisk(id, c.label(), score, levelFor(score), componentConfidence(c), horizon[0], factors, null);
    }

    private void storage(ComponentHealth c, List<RiskFactor> factors, Double[] horizon) {
        double warn = settings.thresholds().storageWarn();
        double crit = settings.thresholds().storageCritical();
        double critDays = settings.risk().storageHorizonCriticalDays();
        double warnDays = settings.risk().storageHorizonWarnDays();

        for (InstanceHealth v : c.instances()) {
            if (v.score() == null) continue;
            Double util = v.metrics().get("utilization");
            if (util != null && util >= warn) {
                double sev = Curves.interpolate(util, new double[][] { { warn, 0.30 }, { crit, 0.70 }, { 100, 1.0 } });
                factors.add(new RiskFactor("STORAGE_NEAR_FULL", v.instance() + " is " + round1(util) + "% full.", sev,
                        Map.of("instance", String.valueOf(v.instance()), "utilization", util)));
            }
            Double days = v.metrics().get("daysToFull");
            if (days != null && days <= warnDays * 3) {
                double sev = Curves.interpolate(days, new double[][] { { critDays, 0.80 }, { warnDays, 0.50 }, { warnDays * 3, 0.15 } });
                factors.add(new RiskFactor("STORAGE_FILLING", v.instance() + " is projected to fill in about " + round1(days)
                        + " days at its recent growth rate.", sev,
                        Map.of("instance", String.valueOf(v.instance()), "daysToFull", days)));
                horizon[0] = horizon[0] == null ? days : Math.min(horizon[0], days);
            }
        }
    }

    private void thermal(ComponentHealth c, List<RiskFactor> factors) {
        InstanceHealth v = first(c);
        if (v == null) return;
        Double fracWarn = v.metrics().get("fractionAboveWarn");
        Double fracCrit = v.metrics().get("fractionAboveCritical");
        Double trend = v.metrics().get("trendPerDay");

        if (fracWarn != null && fracWarn > 0.10) {
            double sev = Curves.ramp(fracWarn, 0.10, 0.80, 0.60);
            factors.add(new RiskFactor("SUSTAINED_HEAT", "CPU was above the warning temperature for " + pct(fracWarn) + "% of the window.",
                    sev, Map.of("fractionPercent", pct(fracWarn))));
        }
        if (fracCrit != null && fracCrit > 0) {
            double sev = 0.5 + Math.min(0.5, fracCrit * 2);
            factors.add(new RiskFactor("CRITICAL_TEMPERATURE", "CPU reached the critical temperature in " + pct(fracCrit) + "% of samples.",
                    sev, Map.of("fractionPercent", pct(fracCrit))));
        }
        if (trend != null && trend >= MIN_RISK_TREND_PER_DAY_TEMP) {
            factors.add(new RiskFactor("RISING_TEMPERATURE", "Average CPU temperature is rising about " + round1(trend) + "\u00B0C per day.",
                    0.30, Map.of("slopePerDay", trend)));
        }
    }

    private void cpu(ComponentHealth c, List<RiskFactor> factors) {
        InstanceHealth v = first(c);
        if (v == null) return;
        Double sat = v.metrics().get("saturationFraction");
        if (sat != null && sat >= 0.25) {
            factors.add(new RiskFactor("SUSTAINED_CPU_SATURATION", "CPU was saturated for " + pct(sat) + "% of the window.",
                    Curves.ramp(sat, 0.25, 1.0, 0.40), Map.of("saturationPercent", pct(sat))));
        }
    }

    private void memory(ComponentHealth c, List<RiskFactor> factors) {
        InstanceHealth v = first(c);
        if (v == null) return;
        Double avg = v.metrics().get("average");
        Double trend = v.metrics().get("trendPerDay");
        if (avg != null && avg >= settings.thresholds().memoryCritical()) {
            factors.add(new RiskFactor("SUSTAINED_MEMORY_PRESSURE", "Average RAM utilization is " + round1(avg) + "%.", 0.50,
                    Map.of("average", avg)));
        }
        if (trend != null && trend >= MIN_RISK_TREND_PER_DAY_MEMORY && avg != null && avg >= settings.thresholds().memoryWarn()) {
            factors.add(new RiskFactor("RISING_MEMORY_USE", "RAM utilization is high and rising about " + round1(trend) + " points per day.",
                    0.40, Map.of("slopePerDay", trend)));
        }
    }

    private void battery(ComponentHealth c, List<RiskFactor> factors) {
        for (InstanceHealth v : c.instances()) {
            if (v.score() == null) continue;
            Double dwell = v.metrics().get("lowDwellFraction");
            if (dwell != null && dwell >= 0.20) {
                factors.add(new RiskFactor("CHRONIC_DEEP_DISCHARGE", String.valueOf(v.instance()) + " spends " + pct(dwell)
                        + "% of the time at low charge.", Curves.ramp(dwell, 0.20, 0.80, 0.50),
                        Map.of("dwellPercent", pct(dwell))));
            }
        }
    }

    private void reliability(ComponentHealth c, List<RiskFactor> factors) {
        InstanceHealth v = first(c);
        if (v == null) return;
        double hw = m(v, "hardwareEvents");
        double st = m(v, "storageEvents");
        double pw = m(v, "powerEvents");
        double last24 = m(v, "eventsLast24h");
        double total = m(v, "eventsInWindow");
        double baseline = m(v, "dailyBaseline");

        if (hw > 0) {
            factors.add(new RiskFactor("HARDWARE_ERRORS", (int) hw + " WHEA/hardware error event(s) in the window.",
                    Math.min(0.9, 0.40 + 0.10 * hw), Map.of("count", hw)));
        }
        if (st > 0) {
            factors.add(new RiskFactor("STORAGE_ERRORS", (int) st + " disk/file-system error event(s) in the window.",
                    Math.min(0.85, 0.35 + 0.05 * st), Map.of("count", st)));
        }
        if (pw > 0) {
            factors.add(new RiskFactor("POWER_INSTABILITY", (int) pw + " kernel-power event(s) in the window.",
                    Math.min(0.65, 0.25 + 0.10 * pw), Map.of("count", pw)));
        }
        if (last24 >= 3 && last24 > 2 * Math.max(1.0, baseline)) {
            factors.add(new RiskFactor("EVENT_ESCALATION", "Events in the last 24 h (" + (int) last24 + ") are well above the earlier daily rate ("
                    + round1(baseline) + "/day), out of " + (int) total + " in the window.", 0.30,
                    Map.of("last24h", last24, "dailyBaseline", baseline)));
        }
    }

    private ComponentRisk notAssessed(ComponentHealth c, String reason) {
        return new ComponentRisk(c.component(), c.label(), null, RiskLevel.NOT_ASSESSED, Confidence.LOW, null, List.of(), reason);
    }

    private RiskLevel levelFor(int score) {
        var r = settings.risk();
        if (score <= r.lowMax()) return RiskLevel.LOW;
        if (score <= r.moderateMax()) return RiskLevel.MODERATE;
        if (score <= r.highMax()) return RiskLevel.HIGH;
        return RiskLevel.CRITICAL;
    }

    private Confidence componentConfidence(ComponentHealth c) {
        if (c.component() == ComponentId.RELIABILITY) {
            return c.dataCoverage() >= 0.6 ? Confidence.MEDIUM : Confidence.LOW; // history length is unknown for events
        }
        double[] best = longestHistory(c.instances());
        return confidence(best[0], best[1], c.dataCoverage());
    }

    private Confidence overallConfidence(HealthReport h) {
        double[] best = longestHistory(h.components().stream().flatMap(c -> c.instances().stream()).toList());
        return confidence(best[0], best[1], h.dataCoverage());
    }

    /** {span hours, density} of the scored instance with the longest history; {0, 0} when there is none. */
    private static double[] longestHistory(List<InstanceHealth> instances) {
        double span = 0;
        double density = 0;
        for (InstanceHealth i : instances) {
            if (i.score() == null) continue;
            double s = i.metrics().getOrDefault("historySpanHours", 0.0);
            if (s > span) {
                span = s;
                density = i.metrics().getOrDefault("historyDensity", 0.0);
            }
        }
        return new double[] { span, density };
    }

    private static Confidence confidence(double spanHours, double density, double coverage) {
        if (density < SPARSE_DENSITY) return Confidence.LOW;
        if (spanHours >= 7 * 24 - 1 && coverage >= 0.6) return Confidence.HIGH;
        if (spanHours >= 24 && coverage >= 0.3) return Confidence.MEDIUM;
        return Confidence.LOW;
    }

    private static InstanceHealth first(ComponentHealth c) {
        return c.instances().stream().filter(i -> i.score() != null).findFirst().orElse(null);
    }

    private static double m(InstanceHealth v, String key) {
        return v.metrics().getOrDefault(key, 0.0);
    }

    private static double clamp01(double d) {
        return Math.max(0, Math.min(1, d));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double pct(double fraction) {
        return Math.round(fraction * 1000.0) / 10.0;
    }

    /** Exposed for tests: the ordered map of component weights actually in use. */
    Map<ComponentId, Double> weightsInUse() {
        return new LinkedHashMap<>(settings.risk().weights());
    }
}
