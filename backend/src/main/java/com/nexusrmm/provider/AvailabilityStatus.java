package com.nexusrmm.provider;

/**
 * Hardware capability model shared by every {@link MetricProvider}.
 *
 * This is the single source of truth for whether a reading can be trusted.
 * A provider reporting {@code UNAVAILABLE} or {@code UNSUPPORTED} must
 * still return a reading object (with value = null) rather than being
 * silently skipped, so the frontend can render an honest "N/A" state
 * instead of a gap that looks like a bug.
 */
public enum AvailabilityStatus {
    /** The provider produced a real, trustworthy value this cycle. */
    AVAILABLE,
    /** The provider is supported on this platform but returned nothing this cycle. */
    UNAVAILABLE,
    /** This platform/hardware does not expose the underlying sensor at all. */
    UNSUPPORTED,
    /** The sensor exists but the process lacks permission to read it. */
    PERMISSION_REQUIRED,
    /** The metric does not apply to this device class (e.g. battery on a desktop). */
    NOT_APPLICABLE,
    /** The provider attempted collection and failed unexpectedly. */
    ERROR
}
