package com.nexusrmm.health.model;

public enum RiskLevel {
    LOW, MODERATE, HIGH, CRITICAL,
    /** No usable evidence, or this component yields no failure indicators. Never rendered as LOW. */
    NOT_ASSESSED
}
