package com.nexusrmm.health.analysis;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.model.ComponentId;

/** RAM utilization health. Thresholds: nexus.health.thresholds.memory-warn / memory-critical. */
public final class MemoryAnalyzer extends UtilizationAnalyzer {

    public MemoryAnalyzer() {
        super(ComponentId.MEMORY, MetricType.RAM_UTILIZATION, "RAM utilization");
    }

    @Override
    protected double warn(AnalysisContext ctx) {
        return ctx.settings().thresholds().memoryWarn();
    }

    @Override
    protected double critical(AnalysisContext ctx) {
        return ctx.settings().thresholds().memoryCritical();
    }
}
