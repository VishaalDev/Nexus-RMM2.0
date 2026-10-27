package com.nexusrmm.advisor.model;

public enum AlertType {
    /** A WARNING/CRITICAL issue appeared. */
    NEW,
    /** An existing issue got worse (e.g. WARNING to CRITICAL). */
    ESCALATED,
    /** A WARNING/CRITICAL issue stayed absent long enough to be considered resolved. */
    RESOLVED
}
