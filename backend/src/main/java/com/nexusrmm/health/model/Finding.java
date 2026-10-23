package com.nexusrmm.health.model;

import java.util.Map;

/**
 * One explainable observation. {@code evidence} carries the actual numbers
 * the message was derived from, so the UI (and a reviewer) can see why it
 * fired rather than trusting a sentence.
 */
public record Finding(Severity severity, String code, String message, Map<String, Object> evidence) {

    public Finding {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    public static Finding info(String code, String message, Map<String, Object> evidence) {
        return new Finding(Severity.INFO, code, message, evidence);
    }

    public static Finding warning(String code, String message, Map<String, Object> evidence) {
        return new Finding(Severity.WARNING, code, message, evidence);
    }

    public static Finding critical(String code, String message, Map<String, Object> evidence) {
        return new Finding(Severity.CRITICAL, code, message, evidence);
    }
}
