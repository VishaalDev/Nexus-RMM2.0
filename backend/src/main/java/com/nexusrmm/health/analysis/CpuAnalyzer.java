package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentId;

/** CPU utilization health. Thresholds: nexus.health.thresholds.cpu-warn / cpu-critical. */
public final class CpuAnalyzer extends UtilizationAnalyzer {

    public CpuAnalyzer() {
        super(ComponentId.CPU, MetricType.CPU_UTILIZATION, "CPU utilization");
    }

    @Override
    protected double warn(AnalysisContext ctx) {
        return ctx.settings().thresholds().cpuWarn();
    }

    @Override
    protected double critical(AnalysisContext ctx) {
        return ctx.settings().thresholds().cpuCritical();
    }
}
