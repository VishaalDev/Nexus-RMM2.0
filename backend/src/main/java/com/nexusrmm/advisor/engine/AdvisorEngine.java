package com.nexusrmm.advisor.engine;

import com.nexusrmm.advisor.config.AdvisorSettings;
import com.nexusrmm.advisor.model.Issue;
import com.nexusrmm.advisor.model.Recommendation;
import com.nexusrmm.advisor.process.ProcessGroup;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;
import com.nexusrmm.health.model.ComponentRisk;
import com.nexusrmm.health.model.Confidence;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.model.RiskFactor;
import com.nexusrmm.health.model.RiskReport;
import com.nexusrmm.health.model.Severity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns Module 2's findings, the risk assessment and (optionally) a process
 * snapshot into a ranked list of {@link Issue}s with concrete advice.
 *
 * <p>Principles, mirroring the rest of the project:
 * <ul>
 *   <li>Everything stated is derived from data the system actually holds; where
 *       signals are combined (e.g. high CPU plus the top process) the
 *       statement says what each part is based on.</li>
 *   <li>Correlation is hedged: heat "most likely" load-driven, never "caused by".</li>
 *   <li>A WARNING/CRITICAL finding no rule recognises still becomes an issue
 *       (generic advice) so new analyzers can never be silently ignored.</li>
 *   <li>A component that could not be assessed is reported as a visibility gap,
 *       never as healthy.</li>
 * </ul>
 * Pure and stateless: lifecycle (first seen, resolved) is {@link AlertTracker}'s job.
 */
public final class AdvisorEngine {

    /** Input of one evaluation. {@code processes} may be null (sampler disabled or not yet run). */
    public record Input(HealthReport health, RiskReport risk, ProcessSnapshot processes) {
    }

    /** CPU average (percent) above which heat is considered "probably load-driven". */
    static final double LOAD_DRIVEN_CPU = 60;
    /** CPU average (percent) below which load cannot plausibly explain high heat. */
    static final double LOAD_UNRELATED_CPU = 40;

    private static final Set<String> STORAGE_CODES = Set.of("STORAGE_NEARLY_FULL", "STORAGE_HIGH_UTILIZATION",
            "STORAGE_FILLING_FAST", "STORAGE_FILLING");
    private static final Set<String> THERMAL_NOW = Set.of("THERMAL_CRITICAL", "THERMAL_HIGH", "THERMAL_CRITICAL_EXCURSIONS");
    private static final Set<String> BATTERY_CODES = Set.of("BATTERY_CRITICALLY_LOW", "BATTERY_LOW", "BATTERY_FREQUENT_DEEP_DISCHARGE");
    private static final List<ComponentId> VISIBILITY_WATCHED = List.of(ComponentId.CPU, ComponentId.MEMORY,
            ComponentId.STORAGE_CAPACITY, ComponentId.THERMAL, ComponentId.RELIABILITY);

    private final AdvisorSettings settings;

    public AdvisorEngine(AdvisorSettings settings) {
        this.settings = settings;
    }

    public List<Issue> evaluate(Input in, Instant now) {
        List<Issue> out = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        HealthReport health = in.health();

        ComponentHealth cpu = health.component(ComponentId.CPU);
        ComponentHealth mem = health.component(ComponentId.MEMORY);
        add(out, loadIssue(cpu, true, in, consumed));
        add(out, trendIssue(cpu, "CPU load", "cpu:trend", in, consumed));
        add(out, loadIssue(mem, false, in, consumed));
        add(out, trendIssue(mem, "Memory use", "memory:trend", in, consumed));
        add(out, thermalIssue(health.component(ComponentId.THERMAL), cpu, in, consumed));
        add(out, thermalTrendIssue(health.component(ComponentId.THERMAL), in, consumed));
        out.addAll(storageIssues(health.component(ComponentId.STORAGE_CAPACITY), in, consumed));
        out.addAll(storageIoIssues(health.component(ComponentId.STORAGE_IO), in, consumed));
        out.addAll(networkIssues(health.component(ComponentId.NETWORK), in, consumed));
        out.addAll(batteryIssues(health.component(ComponentId.BATTERY), in, consumed));
        out.addAll(reliabilityIssues(health.component(ComponentId.RELIABILITY), in, consumed));
        out.addAll(genericIssues(health, in, consumed));
        add(out, visibilityIssue(health, in));

        out.sort(Comparator.comparingInt(Issue::priority).reversed().thenComparing(Issue::key));
        return out;
    }

    // ===== CPU and memory ==============================================================================

    private Issue loadIssue(ComponentHealth c, boolean cpu, Input in, Set<String> consumed) {
        if (c == null) {
            return null;
        }
        String p = c.component().name() + "_";
        Set<String> codes = Set.of(p + "SUSTAINED_CRITICAL", p + "SUSTAINED_HIGH", p + "SATURATED", p + "FREQUENT_SATURATION");
        List<Finding> fs = pick(c.findings(), codes, consumed);
        if (fs.isEmpty()) {
            return null;
        }
        Severity sev = max(fs);
        String noun = cpu ? "CPU" : "Memory";
        String title = cpu
                ? (sev == Severity.CRITICAL ? "CPU is overloaded" : "CPU load is high")
                : (sev == Severity.CRITICAL ? "Memory is nearly exhausted" : "Memory pressure is high");

        Map<String, Object> ev = new LinkedHashMap<>();
        copyMetrics(c, ev, "average", "p95", "max");
        for (Finding f : fs) {
            ev.putAll(f.evidence());
        }

        StringBuilder summary = new StringBuilder(joinMessages(fs, 2));
        List<Recommendation> recs = new ArrayList<>();
        ProcessSnapshot ps = in.processes();
        boolean attributed = false;

        if (ps != null && ps.available()) {
            ProcessGroup top = cpu ? topCpu(ps) : topMemory(ps);
            if (top != null) {
                attributed = true;
                double share = cpu ? safe(top.cpuPercent()) : safe(top.memoryPercent());
                double total = cpu ? safe(ps.totalCpuPercent()) : sumMemoryPercent(ps);
                boolean dominant = total > 0 && share / total >= settings.dominantShare();
                ev.put("topProcess", top.name());
                ev.put("topProcessProcesses", top.processCount());
                if (cpu) {
                    ev.put("topProcessCpuPercent", top.cpuPercent());
                    summary.append(" Right now ").append(top.name()).append(top.processCount() > 1 ? " (" + top.processCount() + " processes)" : "")
                            .append(" is using ").append(fmt(share)).append("% of total CPU")
                            .append(dominant ? ", most of what is in use" : "").append(".");
                } else {
                    ev.put("topProcessMemoryBytes", top.memoryBytes());
                    ev.put("topProcessMemoryPercent", top.memoryPercent());
                    summary.append(" Right now ").append(top.name()).append(top.processCount() > 1 ? " (" + top.processCount() + " processes)" : "")
                            .append(" holds about ").append(gb(top.memoryBytes())).append(" GB (").append(fmt(share)).append("% of RAM)")
                            .append(dominant ? ", the largest share by far" : "").append(".");
                }
                ev.put("dominant", dominant);
                ev.put("attributionNote", "Process figures are from the latest snapshot; the finding covers the last hour, so they can differ.");
                if (top.advice() != null) {
                    recs.add(RecommendationCatalog.processAdvice(top.name(), top.advice(), top.systemComponent()));
                } else if (cpu && share >= 25) {
                    recs.add(RecommendationCatalog.unknownProcess(top.name()));
                }
            }
        }
        int processRecs = recs.size();
        if (cpu) {
            recs.add(RecommendationCatalog.cpuIdentify());
            recs.add(RecommendationCatalog.cpuStartup());
        } else {
            recs.add(RecommendationCatalog.memIdentify());
            recs.add(RecommendationCatalog.memRestartApps());
            Long up = ps == null ? null : ps.uptimeSeconds();
            if (up != null && up >= settings.longUptimeDays() * 86_400L) {
                long days = up / 86_400L;
                ev.put("uptimeDays", days);
                // After the specific top-process advice (if any), before the generic steps.
                recs.add(Math.min(processRecs, recs.size()), RecommendationCatalog.memRestartWindows(days));
            }
            if (!attributed || !Boolean.TRUE.equals(ev.get("dominant"))) {
                if (sev == Severity.CRITICAL) {
                    recs.add(RecommendationCatalog.memMoreRam());
                }
            }
        }
        if (!attributed) {
            ev.put("attribution", processUnavailableReason(ps));
        }
        return build("%s:load".formatted(noun.toLowerCase()), c.component().name(), sev, title, summary.toString(), fs,
                ev, recs, in, null);
    }

    private Issue trendIssue(ComponentHealth c, String what, String key, Input in, Set<String> consumed) {
        if (c == null) {
            return null;
        }
        List<Finding> fs = pick(c.findings(), Set.of(c.component().name() + "_RISING_TREND"), consumed);
        if (fs.isEmpty()) {
            return null;
        }
        return build(key, c.component().name(), Severity.INFO, what + " has been trending upward", joinMessages(fs, 1), fs,
                new LinkedHashMap<>(fs.get(0).evidence()), List.of(RecommendationCatalog.trendWatch(what.toLowerCase())), in, null);
    }

    // ===== Thermal =====================================================================================

    private Issue thermalIssue(ComponentHealth t, ComponentHealth cpu, Input in, Set<String> consumed) {
        if (t == null) {
            return null;
        }
        List<Finding> fs = pick(t.findings(), THERMAL_NOW, consumed);
        if (fs.isEmpty()) {
            return null;
        }
        Severity sev = max(fs);
        Map<String, Object> ev = new LinkedHashMap<>();
        for (Finding f : fs) {
            ev.putAll(f.evidence());
        }
        StringBuilder summary = new StringBuilder(joinMessages(fs, 2));
        if (sev == Severity.CRITICAL) {
            summary.append(" At this level a processor slows itself down (throttles) to protect itself, which makes the computer feel slow.");
        }
        List<Recommendation> recs = new ArrayList<>();
        Double cpuAvg = metric(cpu, "average");
        if (cpuAvg != null) {
            ev.put("cpuAverage", cpuAvg);
            if (cpuAvg >= LOAD_DRIVEN_CPU) {
                summary.append(" CPU averaged ").append(fmt(cpuAvg)).append("% over the same hour, so the heat is most likely load-driven.");
                recs.add(RecommendationCatalog.thermalReduceLoad());
                recs.add(RecommendationCatalog.thermalAirflow());
                recs.add(RecommendationCatalog.thermalPowerMode());
            } else if (cpuAvg < LOAD_UNRELATED_CPU) {
                summary.append(" CPU load was only ").append(fmt(cpuAvg)).append("% on average, so workload does not explain it; cooling (dust, fans, airflow) is the more likely cause.");
                recs.add(RecommendationCatalog.thermalAirflow());
                recs.add(RecommendationCatalog.thermalCooling());
                recs.add(RecommendationCatalog.thermalPowerMode());
            } else {
                recs.add(RecommendationCatalog.thermalAirflow());
                recs.add(RecommendationCatalog.thermalPowerMode());
                recs.add(RecommendationCatalog.thermalCooling());
            }
        } else {
            recs.add(RecommendationCatalog.thermalAirflow());
            recs.add(RecommendationCatalog.thermalCooling());
        }
        return build("thermal:heat", "THERMAL", sev, sev == Severity.CRITICAL ? "CPU is running dangerously hot" : "CPU is running hot",
                summary.toString(), fs, ev, recs, in, null);
    }

    private Issue thermalTrendIssue(ComponentHealth t, Input in, Set<String> consumed) {
        if (t == null) {
            return null;
        }
        List<Finding> fs = pick(t.findings(), Set.of("THERMAL_RISING_TREND"), consumed);
        if (fs.isEmpty()) {
            return null;
        }
        return build("thermal:trend", "THERMAL", Severity.INFO, "CPU temperature is trending upward", joinMessages(fs, 1), fs,
                new LinkedHashMap<>(fs.get(0).evidence()),
                List.of(RecommendationCatalog.thermalAirflow(), RecommendationCatalog.thermalCooling()), in, null);
    }

    // ===== Storage =====================================================================================

    private List<Issue> storageIssues(ComponentHealth c, Input in, Set<String> consumed) {
        List<Issue> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (InstanceHealth ih : c.instances()) {
            List<Finding> fs = pick(ih.findings(), STORAGE_CODES, consumed);
            if (fs.isEmpty()) {
                continue;
            }
            String label = ih.instance() == null ? "Storage" : ih.instance();
            Severity sev = max(fs);
            String title;
            if (has(fs, "STORAGE_NEARLY_FULL")) {
                title = label + " is almost full";
            } else if (has(fs, "STORAGE_HIGH_UTILIZATION")) {
                title = label + " is getting full";
            } else if (has(fs, "STORAGE_FILLING_FAST")) {
                title = label + " is filling up fast";
            } else {
                title = label + " is slowly filling up";
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("instance", label);
            copy(ih.metrics(), ev, "utilization", "growthPerDay", "daysToFull");
            Double days = ih.metrics().get("daysToFull");

            List<Recommendation> recs = new ArrayList<>();
            if (sev == Severity.CRITICAL || (days != null && days <= 7)) {
                recs.add(RecommendationCatalog.storageUrgent(label));
            }
            recs.add(RecommendationCatalog.storageCleanup());
            recs.add(RecommendationCatalog.storageLargeFiles());
            recs.add(RecommendationCatalog.storageUninstall());
            recs.add(RecommendationCatalog.storageMove());
            out.add(build("storage:" + label, "STORAGE_CAPACITY", sev, title, joinMessages(fs, 2), fs, ev, recs, in, days));
        }
        return out;
    }

    private List<Issue> storageIoIssues(ComponentHealth c, Input in, Set<String> consumed) {
        List<Issue> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (InstanceHealth ih : c.instances()) {
            List<Finding> fs = ih.findings().stream()
                    .filter(f -> f.code().startsWith("STORAGE_IO_") && f.code().endsWith("_SURGE")).toList();
            c.findings().stream().filter(f -> f.code().startsWith("STORAGE_IO_") && f.code().endsWith("_SURGE"))
                    .forEach(f -> consumed.add(consumedKey(f)));
            if (fs.isEmpty()) {
                continue;
            }
            fs.forEach(f -> consumed.add(consumedKey(f)));
            String label = ih.instance() == null ? "Disk" : ih.instance();
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("instance", label);
            fs.forEach(f -> ev.putAll(f.evidence()));
            out.add(build("storage-io:" + label, "STORAGE_IO", max(fs), "Unusual disk activity on " + label,
                    joinMessages(fs, 2), fs, ev, List.of(RecommendationCatalog.ioBackground(), RecommendationCatalog.ioIdentify()), in, null));
        }
        return out;
    }

    // ===== Network =====================================================================================

    private List<Issue> networkIssues(ComponentHealth c, Input in, Set<String> consumed) {
        List<Issue> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        List<Finding> offline = pick(c.findings(), Set.of("NETWORK_NO_ACTIVE_ADAPTER"), consumed);
        if (!offline.isEmpty()) {
            out.add(build("network:offline", "NETWORK", max(offline), "No network connection detected", joinMessages(offline, 1),
                    offline, new LinkedHashMap<>(offline.get(0).evidence()),
                    List.of(RecommendationCatalog.netReconnect(), RecommendationCatalog.netDriver()), in, null));
        }
        for (InstanceHealth ih : c.instances()) {
            List<Finding> fs = ih.findings().stream()
                    .filter(f -> f.code().equals("NETWORK_RX_SURGE") || f.code().equals("NETWORK_TX_SURGE")).toList();
            if (fs.isEmpty()) {
                continue;
            }
            fs.forEach(f -> consumed.add(consumedKey(f)));
            String label = ih.instance() == null ? "network" : ih.instance();
            boolean tx = fs.stream().anyMatch(f -> f.code().equals("NETWORK_TX_SURGE"));
            List<Recommendation> recs = new ArrayList<>(List.of(RecommendationCatalog.netBackground(), RecommendationCatalog.netIdentify()));
            if (tx) {
                recs.add(RecommendationCatalog.netUnexpectedUpload());
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("instance", label);
            fs.forEach(f -> ev.putAll(f.evidence()));
            out.add(build("network:" + label, "NETWORK", max(fs), "Unusual network traffic on " + label, joinMessages(fs, 2),
                    fs, ev, recs, in, null));
        }
        return out;
    }

    // ===== Battery =====================================================================================

    private List<Issue> batteryIssues(ComponentHealth c, Input in, Set<String> consumed) {
        List<Issue> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        for (InstanceHealth ih : c.instances()) {
            List<Finding> fs = ih.findings().stream().filter(f -> BATTERY_CODES.contains(f.code())).toList();
            c.findings().stream().filter(f -> BATTERY_CODES.contains(f.code())).forEach(f -> consumed.add(consumedKey(f)));
            if (fs.isEmpty()) {
                continue;
            }
            fs.forEach(f -> consumed.add(consumedKey(f)));
            String label = ih.instance() == null ? "Battery" : ih.instance();
            List<Recommendation> recs = new ArrayList<>();
            String title;
            if (has(fs, "BATTERY_CRITICALLY_LOW")) {
                title = label + " is critically low";
                recs.add(RecommendationCatalog.batteryPlugIn());
            } else if (has(fs, "BATTERY_LOW")) {
                title = label + " is low";
                recs.add(RecommendationCatalog.batteryRecharge());
            } else {
                title = label + " is often drained very low";
            }
            if (has(fs, "BATTERY_FREQUENT_DEEP_DISCHARGE")) {
                recs.add(RecommendationCatalog.batteryHabits());
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("instance", label);
            fs.forEach(f -> ev.putAll(f.evidence()));
            out.add(build("battery:" + label, "BATTERY", max(fs), title, joinMessages(fs, 2), fs, ev, recs, in, null));
        }
        return out;
    }

    // ===== Reliability =================================================================================

    private List<Issue> reliabilityIssues(ComponentHealth c, Input in, Set<String> consumed) {
        List<Issue> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        ComponentRisk risk = in.risk() == null ? null : in.risk().component(ComponentId.RELIABILITY);
        boolean escalating = risk != null && risk.factors().stream().anyMatch(f -> f.code().equals("EVENT_ESCALATION"));
        for (Finding f : c.findings()) {
            if (!f.code().startsWith("EVENTS_")) {
                continue;
            }
            consumed.add(consumedKey(f));
            String cat = f.code().substring("EVENTS_".length());
            String title;
            List<Recommendation> recs;
            switch (cat) {
                case "HARDWARE" -> {
                    title = "Hardware error events";
                    recs = RecommendationCatalog.hardwareEvents();
                }
                case "STORAGE" -> {
                    title = "Disk error events";
                    recs = RecommendationCatalog.storageEvents();
                }
                case "POWER" -> {
                    title = "Power instability events";
                    recs = RecommendationCatalog.powerEvents();
                }
                case "DRIVER" -> {
                    title = "Driver problem events";
                    recs = RecommendationCatalog.driverEvents();
                }
                default -> {
                    title = "Other system events";
                    recs = RecommendationCatalog.otherEvents();
                }
            }
            String summary = f.message();
            Map<String, Object> ev = new LinkedHashMap<>(f.evidence());
            if (escalating && f.severity() != Severity.INFO) {
                summary += " The rate in the last 24 hours is well above the earlier daily rate.";
                ev.put("escalating", true);
            }
            out.add(build("reliability:" + cat.toLowerCase(), "RELIABILITY", f.severity(), title, summary,
                    List.of(f), ev, recs, in, null));
        }
        return out;
    }

    // ===== Catch-all and visibility ====================================================================

    private List<Issue> genericIssues(HealthReport health, Input in, Set<String> consumed) {
        List<Issue> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ComponentHealth c : health.components()) {
            List<Finding> all = new ArrayList<>(c.findings());
            c.instances().forEach(i -> all.addAll(i.findings()));
            for (Finding f : all) {
                if (f.severity() == Severity.INFO || consumed.contains(consumedKey(f))) {
                    continue;
                }
                if (!seen.add(c.component() + ":" + f.code())) {
                    continue;
                }
                out.add(build(c.component().name().toLowerCase() + ":" + f.code().toLowerCase(), c.component().name(),
                        f.severity(), c.label() + ": " + f.code().toLowerCase().replace('_', ' '), f.message(), List.of(f),
                        new LinkedHashMap<>(f.evidence()), List.of(RecommendationCatalog.reviewHealth(c.label())), in, null));
            }
        }
        return out;
    }

    private Issue visibilityIssue(HealthReport health, Input in) {
        List<ComponentHealth> blind = new ArrayList<>();
        for (ComponentId id : VISIBILITY_WATCHED) {
            ComponentHealth c = health.component(id);
            if (c != null && c.status() == HealthStatus.NO_DATA) {
                blind.add(c);
            }
        }
        if (blind.isEmpty()) {
            return null;
        }
        boolean warmup = blind.stream().map(ComponentHealth::component)
                .filter(id -> id == ComponentId.CPU || id == ComponentId.MEMORY || id == ComponentId.STORAGE_CAPACITY)
                .count() == 3;
        Map<String, Object> ev = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (ComponentHealth c : blind) {
            ev.put(c.component().name(), c.noDataReason() == null ? "No data." : c.noDataReason());
        }
        List<Recommendation> recs = new ArrayList<>();
        String title;
        if (warmup) {
            title = "Still collecting data";
            sb.append("CPU, memory and storage have no usable samples yet, so nothing can be scored.");
            recs.add(RecommendationCatalog.letDataAccumulate());
        } else {
            title = "Some areas cannot be assessed";
            sb.append("These areas have no usable data, so the advisor says nothing about them (this is not the same as healthy): ");
            for (int i = 0; i < blind.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(blind.get(i).label());
            }
            sb.append('.');
            if (blind.stream().anyMatch(c -> c.component() == ComponentId.THERMAL)) {
                recs.add(RecommendationCatalog.seeLimitations("docs/hardware-monitoring.md (CPU temperature)"));
            }
            if (blind.stream().anyMatch(c -> c.component() != ComponentId.THERMAL)) {
                recs.add(RecommendationCatalog.letDataAccumulate());
            }
        }
        return new Issue(warmup ? "visibility:warmup" : "visibility:gaps", "VISIBILITY", Severity.INFO, title, sb.toString(),
                List.of(), ev, recs, Confidence.HIGH, 100, null, null);
    }

    // ===== Helpers =====================================================================================

    private Issue build(String key, String category, Severity sev, String title, String summary, List<Finding> source,
                        Map<String, Object> evidence, List<Recommendation> recs, Input in, Double horizonDays) {
        ComponentId id = componentOf(category);
        ComponentRisk risk = id == null || in.risk() == null ? null : in.risk().component(id);
        Confidence conf = risk != null && risk.confidence() != null ? risk.confidence() : Confidence.MEDIUM;
        int base = switch (sev) {
            case CRITICAL -> 300;
            case WARNING -> 200;
            case INFO -> 100;
        };
        int riskBump = risk != null && risk.riskScore() != null ? risk.riskScore() / 2 : 0;
        Double h = horizonDays != null ? horizonDays : (risk == null ? null : risk.horizonDays());
        int urgency = h == null ? 0 : (h <= 7 ? 40 : (h <= 30 ? 20 : 0));
        int confBump = conf == Confidence.HIGH ? 10 : (conf == Confidence.MEDIUM ? 5 : 0);
        if (risk != null && sev != Severity.INFO && risk.factors().stream().map(RiskFactor::code).anyMatch("EVENT_ESCALATION"::equals)) {
            urgency += 15;
        }
        List<String> codes = source.stream().map(Finding::code).distinct().toList();
        if (h != null && !evidence.containsKey("horizonDays")) {
            evidence.put("horizonDays", h);
        }
        return new Issue(key, category, sev, title, summary, codes, evidence, dedupe(recs), conf,
                base + riskBump + urgency + confBump, null, null);
    }

    private static List<Recommendation> dedupe(List<Recommendation> recs) {
        Set<String> seen = new HashSet<>();
        List<Recommendation> out = new ArrayList<>();
        for (Recommendation r : recs) {
            if (seen.add(r.code())) {
                out.add(r);
            }
        }
        return out;
    }

    private static ComponentId componentOf(String category) {
        try {
            return ComponentId.valueOf(category);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void add(List<Issue> out, Issue i) {
        if (i != null) {
            out.add(i);
        }
    }

    /** Findings with one of {@code codes}; marks them consumed so the catch-all skips them. */
    private static List<Finding> pick(List<Finding> findings, Set<String> codes, Set<String> consumed) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : findings) {
            if (codes.contains(f.code())) {
                consumed.add(consumedKey(f));
                out.add(f);
            }
        }
        return out;
    }

    private static String consumedKey(Finding f) {
        return f.code() + "|" + f.message();
    }

    private static boolean has(List<Finding> fs, String code) {
        return fs.stream().anyMatch(f -> f.code().equals(code));
    }

    private static Severity max(List<Finding> fs) {
        Severity best = Severity.INFO;
        for (Finding f : fs) {
            if (f.severity().ordinal() > best.ordinal()) {
                best = f.severity();
            }
        }
        return best;
    }

    private static String joinMessages(List<Finding> fs, int limit) {
        List<String> msgs = fs.stream().sorted(Comparator.comparing((Finding f) -> f.severity().ordinal()).reversed())
                .map(Finding::message).distinct().limit(limit).toList();
        return String.join(" ", msgs);
    }

    private static Double metric(ComponentHealth c, String name) {
        if (c == null) {
            return null;
        }
        for (InstanceHealth ih : c.instances()) {
            Double v = ih.metrics().get(name);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static void copyMetrics(ComponentHealth c, Map<String, Object> ev, String... names) {
        for (String n : names) {
            Double v = metric(c, n);
            if (v != null) {
                ev.put(n, v);
            }
        }
    }

    private static void copy(Map<String, Double> src, Map<String, Object> dst, String... names) {
        for (String n : names) {
            if (src.get(n) != null) {
                dst.put(n, src.get(n));
            }
        }
    }

    private ProcessGroup topCpu(ProcessSnapshot ps) {
        if (!ps.cpuAvailable() || ps.topByCpu().isEmpty()) {
            return null;
        }
        ProcessGroup g = ps.topByCpu().get(0);
        return safe(g.cpuPercent()) >= settings.minBlameCpuPercent() ? g : null;
    }

    private ProcessGroup topMemory(ProcessSnapshot ps) {
        if (ps.topByMemory().isEmpty()) {
            return null;
        }
        ProcessGroup g = ps.topByMemory().get(0);
        return safe(g.memoryPercent()) >= settings.minBlameMemoryPercent() ? g : null;
    }

    private static double sumMemoryPercent(ProcessSnapshot ps) {
        return ps.topByMemory().stream().mapToDouble(g -> safe(g.memoryPercent())).sum();
    }

    private static String processUnavailableReason(ProcessSnapshot ps) {
        if (ps == null) {
            return "Process insight is not running, so no program could be named.";
        }
        if (!ps.available()) {
            return ps.note() == null ? "No process snapshot is available." : ps.note();
        }
        return "No single program stood out enough to name.";
    }

    private static double safe(Double d) {
        return d == null ? 0 : d;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private static String gb(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1_073_741_824.0);
    }
}
