package com.nexusrmm.advisor.service;

import com.nexusrmm.advisor.process.ProcessSource;
import com.nexusrmm.advisor.process.RawProcess;
import org.springframework.stereotype.Component;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Production {@link ProcessSource}: reads the process table through OSHI,
 * sharing the single SystemInfo created by HardwareConfig. Per-process CPU
 * is the tick delta against the previous capture of the same process, the
 * same honest-baseline approach CpuUtilizationProvider uses: the first
 * capture has no baseline and says so.
 *
 * <p>Only pid, name, resident size and thread count are read. Command lines,
 * user names and paths are never requested, because they commonly contain
 * secrets and nothing here needs them.
 */
@Component
public class OshiProcessSource implements ProcessSource {

    private final OperatingSystem os;
    private final HardwareAbstractionLayer hal;
    private Map<Integer, OSProcess> previous = Map.of();

    public OshiProcessSource(OperatingSystem os, HardwareAbstractionLayer hal) {
        this.os = os;
        this.hal = hal;
    }

    @Override
    public synchronized Capture capture() {
        List<OSProcess> processes = os.getProcesses(null, null, 0);
        boolean baseline = !previous.isEmpty();
        Map<Integer, OSProcess> current = new HashMap<>(processes.size() * 2);
        List<RawProcess> raw = new ArrayList<>(processes.size());
        for (OSProcess p : processes) {
            int pid = p.getProcessID();
            current.put(pid, p);
            Double cores = null;
            OSProcess prior = previous.get(pid);
            if (prior != null) {
                double load = p.getProcessCpuLoadBetweenTicks(prior);
                if (!Double.isNaN(load) && !Double.isInfinite(load) && load >= 0) {
                    cores = load;
                }
            }
            raw.add(new RawProcess(pid, p.getName(), cores, p.getResidentSetSize(), p.getThreadCount()));
        }
        previous = current;
        int cpus = Math.max(1, hal.getProcessor().getLogicalProcessorCount());
        return new Capture(raw, cpus, hal.getMemory().getTotal(), os.getSystemUptime(), baseline);
    }
}
