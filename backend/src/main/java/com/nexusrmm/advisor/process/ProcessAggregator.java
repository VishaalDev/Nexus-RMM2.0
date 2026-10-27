package com.nexusrmm.advisor.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a raw process list into grouped, ranked, normalised figures. Pure
 * function of its input: all platform access happens in a
 * {@link ProcessSource}.
 */
public final class ProcessAggregator {

    private static final String NO_BASELINE_NOTE =
            "Baseline captured; per-process CPU appears after the next sample.";
    private static final String MEMORY_NOTE =
            "Memory is the sum of each process's resident set; memory shared between a group's processes can be counted more than once, so large groups may be overstated.";

    private ProcessAggregator() {
    }

    private static final class Acc {
        final String display;
        int count;
        double cpuCores;
        boolean anyCpu;
        long bytes;
        int threads;

        Acc(String display) {
            this.display = display;
        }
    }

    public static ProcessSnapshot aggregate(ProcessSource.Capture capture, int topN, Instant now) {
        if (capture == null || capture.processes() == null || capture.processes().isEmpty()) {
            return ProcessSnapshot.unavailable(now, "The operating system returned no process list.");
        }
        int cpus = Math.max(1, capture.logicalCpus());
        boolean cpuReady = capture.cpuBaselineReady();

        Map<String, Acc> groups = new LinkedHashMap<>();
        int processCount = 0;
        int threadCount = 0;
        for (RawProcess p : capture.processes()) {
            if (p == null || p.name() == null || p.name().isBlank()) {
                continue;
            }
            processCount++;
            threadCount += Math.max(0, p.threads());
            String key = ProcessKnowledge.normalize(p.name());
            // The idle pseudo-process is "CPU not in use", not a consumer.
            if (p.pid() == 0 || key.equals("system idle process") || key.equals("idle")) {
                continue;
            }
            Acc a = groups.computeIfAbsent(key, k -> new Acc(p.name().trim()));
            a.count++;
            a.bytes += Math.max(0L, p.residentBytes());
            a.threads += Math.max(0, p.threads());
            if (cpuReady && p.cpuCores() != null && p.cpuCores() >= 0) {
                a.cpuCores += p.cpuCores();
                a.anyCpu = true;
            }
        }

        long totalMem = capture.totalMemoryBytes();
        List<ProcessGroup> all = new ArrayList<>();
        double totalCpu = 0;
        for (Map.Entry<String, Acc> e : groups.entrySet()) {
            Acc a = e.getValue();
            Double cpu = cpuReady ? clamp(100.0 * a.cpuCores / cpus) : null;
            if (cpu != null) {
                totalCpu += cpu;
            }
            Double mem = totalMem > 0 ? clamp(100.0 * a.bytes / totalMem) : null;
            var k = ProcessKnowledge.lookup(a.display);
            all.add(new ProcessGroup(a.display, a.count, cpu == null ? null : round1(cpu), a.bytes,
                    mem == null ? null : round1(mem), a.threads,
                    k.map(ProcessKnowledge.Entry::description).orElse(null),
                    k.map(ProcessKnowledge.Entry::advice).orElse(null),
                    k.map(ProcessKnowledge.Entry::systemComponent).orElse(false)));
        }

        List<ProcessGroup> byCpu = cpuReady
                ? all.stream()
                        .filter(g -> g.cpuPercent() != null && g.cpuPercent() > 0)
                        .sorted(Comparator.comparing(ProcessGroup::cpuPercent).reversed()
                                .thenComparing(ProcessGroup::name))
                        .limit(topN).toList()
                : List.of();
        List<ProcessGroup> byMem = all.stream()
                .sorted(Comparator.comparingLong(ProcessGroup::memoryBytes).reversed()
                        .thenComparing(ProcessGroup::name))
                .limit(topN).toList();

        String note = cpuReady ? MEMORY_NOTE : NO_BASELINE_NOTE + " " + MEMORY_NOTE;
        return new ProcessSnapshot(now, true, cpuReady, processCount, threadCount, capture.uptimeSeconds(),
                totalMem > 0 ? totalMem : null, cpuReady ? round1(totalCpu) : null, byCpu, byMem, note);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
