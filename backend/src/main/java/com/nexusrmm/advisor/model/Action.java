package com.nexusrmm.advisor.model;

import com.nexusrmm.health.model.Severity;

/** A recommendation lifted out of its issue for the "do this first" list. */
public record Action(String issueKey, String issueTitle, Severity severity, Recommendation recommendation) {
}
