package com.nexusrmm.advisor.config;

import java.time.Duration;

/**
 * Framework-free tunables of the advisor (Module 4). Validated on
 * construction so a bad configuration fails at startup instead of producing
 * silently odd advice. Bound from {@code nexus.advisor.*} by
 * {@link AdvisorProperties}.
 *
 * @param processTopN               groups kept per ranking in a process snapshot
 * @param minBlameCpuPercent        a process group is named as a CPU contributor only when it
 *                                  uses at least this share of the whole machine's CPU
 * @param minBlameMemoryPercent     same, for RAM
 * @param dominantShare             a group is called "dominant" when it accounts for at least this
 *                                  fraction of all CPU / memory in use by ranked groups
 * @param longUptimeDays            uptime from which a restart is suggested alongside memory pressure
 * @param resolveAfterAbsent        consecutive evaluations an issue must be absent before it is
 *                                  declared resolved (debounces flapping)
 * @param maxAlertEvents            bounded size of the in-memory alert log
 * @param topActions                number of "do this first" actions in a report
 * @param cacheTtl                  how long an evaluated report is reused
 */
public record AdvisorSettings(
        int processTopN,
        double minBlameCpuPercent,
        double minBlameMemoryPercent,
        double dominantShare,
        int longUptimeDays,
        int resolveAfterAbsent,
        int maxAlertEvents,
        int topActions,
        Duration cacheTtl
) {

    public AdvisorSettings {
        require(processTopN >= 1 && processTopN <= 50, "processTopN must be 1..50");
        require(minBlameCpuPercent >= 0 && minBlameCpuPercent <= 100, "minBlameCpuPercent must be 0..100");
        require(minBlameMemoryPercent >= 0 && minBlameMemoryPercent <= 100, "minBlameMemoryPercent must be 0..100");
        require(dominantShare > 0 && dominantShare <= 1, "dominantShare must be in (0,1]");
        require(longUptimeDays >= 1, "longUptimeDays must be >= 1");
        require(resolveAfterAbsent >= 1, "resolveAfterAbsent must be >= 1");
        require(maxAlertEvents >= 10 && maxAlertEvents <= 10_000, "maxAlertEvents must be 10..10000");
        require(topActions >= 1 && topActions <= 10, "topActions must be 1..10");
        require(cacheTtl != null && !cacheTtl.isNegative(), "cacheTtl must be >= 0");
    }

    public static AdvisorSettings defaults() {
        return new AdvisorSettings(8, 5.0, 5.0, 0.5, 14, 2, 200, 3, Duration.ofSeconds(10));
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException("Invalid nexus.advisor configuration: " + message);
        }
    }
}
