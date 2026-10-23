package com.nexusrmm.health.model;

import java.util.Map;

/** One contributor to a risk index. {@code severity} is 0..1 and is an ordinal strength, not a probability. */
public record RiskFactor(String code, String description, double severity, Map<String, Object> evidence) {
    public RiskFactor {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
