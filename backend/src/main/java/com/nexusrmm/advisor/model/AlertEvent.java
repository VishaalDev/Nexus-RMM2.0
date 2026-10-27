package com.nexusrmm.advisor.model;

import com.nexusrmm.health.model.Severity;

import java.time.Instant;

/** One entry in the alert log. Kept in memory only (see docs/advisor.md for the consequence). */
public record AlertEvent(Instant time, AlertType type, String issueKey, String category,
                         Severity severity, String title) {
}
