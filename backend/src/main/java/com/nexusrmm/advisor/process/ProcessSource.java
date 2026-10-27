package com.nexusrmm.advisor.process;

/** Platform access seam: production reads OSHI, tests supply fixtures. */
public interface ProcessSource {

    /** One raw capture. {@code cpuBaselineReady} is false on the first capture (no prior sample to diff against). */
    record Capture(java.util.List<RawProcess> processes, int logicalCpus, long totalMemoryBytes,
                   long uptimeSeconds, boolean cpuBaselineReady) {
    }

    Capture capture();
}
