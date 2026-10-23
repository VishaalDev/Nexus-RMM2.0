package com.nexusrmm.health.analysis;

import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.model.Severity;
import com.nexusrmm.health.provider.EventMonitoring;
import com.nexusrmm.health.provider.EventPoint;
import com.nexusrmm.health.stats.Curves;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nexusrmm.health.analysis.AnalyzerSupport.ev;
import static com.nexusrmm.health.analysis.AnalyzerSupport.metrics;

/**
 * Reliability from stored Windows hardware events (WHEA, disk/NTFS/volsnap,
 * kernel power, PnP). These are the strongest failure indicators Module 1
 * collects, so this component carries the highest weight.
 *
 * Each event adds: base(category) x level multiplier (Critical 1.5, else
 * 1.0) x recency factor (<=24h 1.0, <=72h 0.7, older 0.4). Each category is
 * capped, so a storm of identical entries cannot dominate without bound.
 *
 * <pre>
 * category   base  cap
 * HARDWARE    15   60   (WHEA: machine-check / corrected hardware errors)
 * STORAGE     10   60   (disk, Ntfs, volsnap)
 * POWER        8   40   (Kernel-Power, e.g. unexpected shutdown)
 * DRIVER       3   15
 * other        3   15
 * </pre>
 * Total penalty is capped at 100.
 *
 * "Zero events" is only believed when event monitoring is applicable and
 * the last poll succeeded. Otherwise the result is NO_DATA with the
 * monitor's own explanation - an empty list from a monitor that is not
 * running is "unknown", not "no problems". Event history only covers the
 * time the application has been polling.
 */
public final class ReliabilityAnalyzer implements ComponentAnalyzer {

    private record Rule(double base, double cap) {
    }

    private static final Rule HARDWARE = new Rule(15, 60);
    private static final Rule STORAGE = new Rule(10, 60);
    private static final Rule POWER = new Rule(8, 40);
    private static final Rule MINOR = new Rule(3, 15);

    private static final double CRITICAL_LEVEL_MULTIPLIER = 1.5;
    private static final double RECENT_FACTOR = 1.0;
    private static final double MID_FACTOR = 0.7;
    private static final double OLD_FACTOR = 0.4;
    private static final Duration RECENT = Duration.ofHours(24);
    private static final Duration MID = Duration.ofHours(72);

    @Override
    public ComponentId component() {
        return ComponentId.RELIABILITY;
    }

    @Override
    public ComponentHealth analyze(AnalysisContext ctx) {
        String basis = "Hardware-relevant Windows System-log events (Critical/Error) over the last "
                + ctx.settings().windows().eventWindow().toDays() + " days, weighted by category, level and recency. "
                + "Covers only the period the application has been polling.";

        EventMonitoring monitor = ctx.data().eventMonitoring();
        if (!monitor.applicable()) {
            return AnalyzerSupport.assemble(ctx, ComponentId.RELIABILITY, basis,
                    List.of(InstanceHealth.noData(null, "Event monitoring is not applicable or has not run: " + safe(monitor.detail()), 0, 0)),
                    AnalyzerSupport.Aggregation.MIN, List.of(), "Reliability could not be assessed.");
        }
        if (!monitor.lastPollSuccess()) {
            return AnalyzerSupport.assemble(ctx, ComponentId.RELIABILITY, basis,
                    List.of(InstanceHealth.noData(null, "The last event-log poll failed: " + safe(monitor.detail()), 0, 0)),
                    AnalyzerSupport.Aggregation.MIN, List.of(), "Reliability could not be assessed.");
        }

        List<EventPoint> events = ctx.data().events(ctx.eventStart(), ctx.settings().windows().maxEvents());
        Instant now = ctx.now();

        Map<String, Double> penaltyByCat = new LinkedHashMap<>();
        Map<String, Integer> countByCat = new LinkedHashMap<>();
        Map<String, Integer> count24ByCat = new LinkedHashMap<>();
        Map<String, Instant> latestByCat = new LinkedHashMap<>();
        int last24h = 0;

        for (EventPoint e : events) {
            String cat = e.category() == null ? "SYSTEM" : e.category().toUpperCase();
            Rule rule = ruleFor(cat);
            double multiplier = "Critical".equalsIgnoreCase(e.level()) ? CRITICAL_LEVEL_MULTIPLIER : 1.0;
            Duration age = Duration.between(e.time(), now);
            double recency = age.compareTo(RECENT) <= 0 ? RECENT_FACTOR : (age.compareTo(MID) <= 0 ? MID_FACTOR : OLD_FACTOR);

            penaltyByCat.merge(cat, rule.base() * multiplier * recency, Double::sum);
            countByCat.merge(cat, 1, Integer::sum);
            latestByCat.merge(cat, e.time(), (a, b) -> a.isAfter(b) ? a : b);
            if (age.compareTo(RECENT) <= 0) {
                last24h++;
                count24ByCat.merge(cat, 1, Integer::sum);
            }
        }

        double total = 0;
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, Double> entry : penaltyByCat.entrySet()) {
            String cat = entry.getKey();
            double capped = Math.min(entry.getValue(), ruleFor(cat).cap());
            total += capped;
            int count = countByCat.get(cat);
            Severity sev = capped >= 30 ? Severity.CRITICAL : (capped >= 10 ? Severity.WARNING : Severity.INFO);
            findings.add(new Finding(sev, "EVENTS_" + cat,
                    count + " " + cat.toLowerCase() + " event(s) in the last " + ctx.settings().windows().eventWindow().toDays()
                            + " days (" + count24ByCat.getOrDefault(cat, 0) + " in the last 24 h); most recent " + latestByCat.get(cat) + ".",
                    ev("category", cat, "count", count, "last24h", count24ByCat.getOrDefault(cat, 0),
                            "penalty", capped, "mostRecent", latestByCat.get(cat).toString())));
        }
        total = Math.min(100, total);
        int score = Curves.score(total);

        double windowDays = Math.max(1.0, ctx.settings().windows().eventWindow().toHours() / 24.0);
        int older = events.size() - last24h;
        double dailyBaseline = windowDays > 1 ? older / (windowDays - 1) : 0;

        InstanceHealth ih = new InstanceHealth(null, score, Scoring.statusFor(score, ctx.settings()), events.size(), 1.0,
                metrics("eventsInWindow", (double) events.size(), "eventsLast24h", (double) last24h,
                        "hardwareEvents", (double) countByCat.getOrDefault("HARDWARE", 0),
                        "storageEvents", (double) countByCat.getOrDefault("STORAGE", 0),
                        "powerEvents", (double) countByCat.getOrDefault("POWER", 0),
                        "driverEvents", (double) countByCat.getOrDefault("DRIVER", 0),
                        "otherEvents", (double) (countByCat.getOrDefault("SYSTEM", 0)),
                        "hardwareLast24h", (double) count24ByCat.getOrDefault("HARDWARE", 0),
                        "storageLast24h", (double) count24ByCat.getOrDefault("STORAGE", 0),
                        "powerLast24h", (double) count24ByCat.getOrDefault("POWER", 0),
                        "dailyBaseline", dailyBaseline, "penalty", total),
                findings, null);
        return AnalyzerSupport.assemble(ctx, ComponentId.RELIABILITY, basis, List.of(ih), AnalyzerSupport.Aggregation.MIN,
                List.of(), "Reliability could not be assessed.");
    }

    private static Rule ruleFor(String category) {
        return switch (category) {
            case "HARDWARE" -> HARDWARE;
            case "STORAGE" -> STORAGE;
            case "POWER" -> POWER;
            default -> MINOR;
        };
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "no detail reported." : s;
    }
}
