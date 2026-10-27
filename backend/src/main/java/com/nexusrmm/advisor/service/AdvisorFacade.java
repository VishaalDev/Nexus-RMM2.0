package com.nexusrmm.advisor.service;

import com.nexusrmm.advisor.config.AdvisorSettings;
import com.nexusrmm.advisor.engine.AdvisorEngine;
import com.nexusrmm.advisor.engine.AlertTracker;
import com.nexusrmm.advisor.model.Action;
import com.nexusrmm.advisor.model.AdvisorReport;
import com.nexusrmm.advisor.model.AlertEvent;
import com.nexusrmm.advisor.model.Issue;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.health.model.HealthReport;
import com.nexusrmm.health.model.HealthStatus;
import com.nexusrmm.health.model.RiskLevel;
import com.nexusrmm.health.model.Severity;
import com.nexusrmm.health.service.HealthFacade;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Entry point for the advisor: evaluates, tracks issue lifecycle, builds the
 * report. Framework-free (wired by AdvisorModuleConfig). Reports are cached
 * for the configured TTL because the UI polls; every actual evaluation also
 * feeds the {@link AlertTracker}, and a scheduler evaluates periodically so
 * the alert log stays meaningful while nobody has the UI open.
 */
public class AdvisorFacade {

    static final int MAX_ALERT_LIMIT = 200;

    private final Supplier<HealthFacade.Analysis> analysis;
    private final Supplier<ProcessSnapshot> processes;
    private final AdvisorEngine engine;
    private final AlertTracker tracker;
    private final AdvisorSettings settings;
    private final Clock clock;

    private AdvisorReport cached;
    private Instant cachedAt;

    public AdvisorFacade(Supplier<HealthFacade.Analysis> analysis, Supplier<ProcessSnapshot> processes,
                         AdvisorEngine engine, AlertTracker tracker, AdvisorSettings settings, Clock clock) {
        this.analysis = analysis;
        this.processes = processes;
        this.engine = engine;
        this.tracker = tracker;
        this.settings = settings;
        this.clock = clock;
    }

    public synchronized AdvisorReport report() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cachedAt.plus(settings.cacheTtl()))) {
            return cached;
        }
        return evaluateLocked(now);
    }

    /** Forces a fresh evaluation (used by the scheduler). */
    public synchronized AdvisorReport evaluate() {
        return evaluateLocked(clock.instant());
    }

    public List<AlertEvent> alerts(int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_ALERT_LIMIT));
        return tracker.recent(bounded);
    }

    private AdvisorReport evaluateLocked(Instant now) {
        HealthFacade.Analysis a = analysis.get();
        ProcessSnapshot ps = processes.get();
        List<Issue> issues = tracker.update(engine.evaluate(new AdvisorEngine.Input(a.health(), a.risk(), ps), now), now);

        int crit = count(issues, Severity.CRITICAL);
        int warn = count(issues, Severity.WARNING);
        int info = count(issues, Severity.INFO);
        boolean insight = ps != null && ps.available();

        boolean warmingUp = issues.stream().anyMatch(i -> i.key().equals("visibility:warmup"));
        cached = new AdvisorReport(now, headline(a.health(), crit, warn, warmingUp), a.health().overallScore(),
                a.health().overallStatus(), a.health().dataCoverage(),
                a.risk() == null ? RiskLevel.NOT_ASSESSED : a.risk().overallLevel(),
                crit, warn, info, insight, issues, topActions(issues), limitations(a.health(), ps));
        cachedAt = now;
        return cached;
    }

    private static int count(List<Issue> issues, Severity s) {
        return (int) issues.stream().filter(i -> i.severity() == s).count();
    }

    static String headline(HealthReport health, int crit, int warn, boolean warmingUp) {
        if (crit > 0) {
            return plural(crit, "critical issue") + (warn > 0 ? " and " + plural(warn, "warning") : "") + " need attention.";
        }
        if (warn > 0) {
            return plural(warn, "warning") + " worth a look.";
        }
        if (warmingUp) {
            return "Still collecting data; too early to assess this computer.";
        }
        if (health.overallStatus() == HealthStatus.NO_DATA) {
            return "Not enough data yet to assess this computer.";
        }
        if (health.dataCoverage() < 0.5) {
            return "No problems found, but only " + Math.round(health.dataCoverage() * 100)
                    + "% of the health data could be assessed.";
        }
        return "No problems found.";
    }

    private static String plural(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private List<Action> topActions(List<Issue> issues) {
        List<Action> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Issue i : issues) {
            if (i.severity() == Severity.INFO || i.recommendations().isEmpty()) {
                continue;
            }
            var rec = i.recommendations().get(0);
            if (seen.add(rec.code())) {
                out.add(new Action(i.key(), i.title(), i.severity(), rec));
            }
            if (out.size() >= settings.topActions()) {
                break;
            }
        }
        return out;
    }

    private static List<String> limitations(HealthReport health, ProcessSnapshot ps) {
        List<String> out = new ArrayList<>(health.limitations());
        out.add("Advice is rule-based on the signals Nexus-RMM collects. It cannot see per-process disk or network activity, GPU load or drive SMART health, and it never changes anything on your computer itself.");
        if (ps == null || !ps.available()) {
            out.add("Per-process attribution is unavailable"
                    + (ps != null && ps.note() != null ? ": " + ps.note() : " (process sampling is not running)."));
        } else if (!ps.cpuAvailable()) {
            out.add("Per-process CPU needs a second sample, so CPU issues cannot name a program yet.");
        }
        out.add("The alert history is kept in memory and starts empty after every backend restart.");
        return out;
    }
}
