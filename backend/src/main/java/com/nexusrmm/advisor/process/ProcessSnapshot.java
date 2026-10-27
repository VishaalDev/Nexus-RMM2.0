package com.nexusrmm.advisor.process;

import java.time.Instant;
import java.util.List;

/**
 * A point-in-time view of what is running. {@code available} false means
 * "no snapshot", always with a reason in {@code note}: it is never an empty
 * list pretending the machine is idle.
 *
 * @param cpuAvailable true when per-process CPU could be computed (needs two samples)
 * @param totalCpuPercent sum of all group CPU shares, null when unavailable
 */
public record ProcessSnapshot(
        Instant capturedAt,
        boolean available,
        boolean cpuAvailable,
        int processCount,
        int threadCount,
        Long uptimeSeconds,
        Long totalMemoryBytes,
        Double totalCpuPercent,
        List<ProcessGroup> topByCpu,
        List<ProcessGroup> topByMemory,
        String note
) {
    public ProcessSnapshot {
        topByCpu = topByCpu == null ? List.of() : List.copyOf(topByCpu);
        topByMemory = topByMemory == null ? List.of() : List.copyOf(topByMemory);
    }

    public static ProcessSnapshot unavailable(Instant now, String reason) {
        return new ProcessSnapshot(now, false, false, 0, 0, null, null, null, List.of(), List.of(), reason);
    }
}
