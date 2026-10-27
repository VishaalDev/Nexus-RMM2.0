package com.nexusrmm.advisor.process;

/**
 * One operating-system process as read from the platform, reduced to what
 * the advisor needs. Deliberately has no command line, user name or path:
 * those routinely contain secrets (tokens in arguments, user names in
 * paths) and nothing in the advisor needs them.
 *
 * @param cpuCores CPU used since the previous sample, in units of one logical core
 *                 (1.0 = one core fully busy; may exceed 1.0 for multi-threaded processes);
 *                 {@code null} when no baseline exists for this process
 */
public record RawProcess(int pid, String name, Double cpuCores, long residentBytes, int threads) {
}
