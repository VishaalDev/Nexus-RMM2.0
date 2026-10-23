package com.nexusrmm.health.model;

/** NO_DATA is a first-class outcome: "we could not assess this" is never rendered as "healthy". */
public enum HealthStatus {
    HEALTHY, FAIR, DEGRADED, CRITICAL, NO_DATA
}
