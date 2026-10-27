package com.nexusrmm.advisor.process;

/**
 * All processes sharing an executable name (a browser's dozens of helper
 * processes become one row).
 *
 * @param cpuPercent    share of the WHOLE machine's CPU (0..100), null when no CPU baseline yet
 * @param memoryBytes   sum of resident set sizes; shared pages can be counted once per process,
 *                      so a group of many processes may overstate its true footprint
 * @param memoryPercent share of physical RAM, null when total RAM is unknown
 * @param description   plain-language "what is this" for well-known processes, else null
 * @param advice        short tip for well-known processes, else null
 */
public record ProcessGroup(String name, int processCount, Double cpuPercent, long memoryBytes,
                           Double memoryPercent, int threadCount, String description, String advice,
                           boolean systemComponent) {
}
