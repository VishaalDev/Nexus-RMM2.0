package com.nexusrmm.advisor.model;

/** How much work a recommendation is for the person following it. */
public enum Effort {
    /** A minute or two, no special knowledge. */
    EASY,
    /** Several minutes or a settings change worth understanding first. */
    MODERATE,
    /** Needs care: hardware access, command-line tools or a restart-level change. */
    ADVANCED
}
