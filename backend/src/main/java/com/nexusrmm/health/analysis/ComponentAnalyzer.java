package com.nexusrmm.health.analysis;

import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.ComponentId;

/**
 * One analyzer per component. Implementations must be stateless, must not
 * throw for missing data (return a NO_DATA result with a reason instead),
 * and must never turn "no data" into a good score.
 */
public interface ComponentAnalyzer {

    ComponentId component();

    ComponentHealth analyze(AnalysisContext context);
}
