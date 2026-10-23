package com.nexusrmm.health.provider;

import com.nexusrmm.provider.AvailabilityStatus;

import java.time.Instant;

/** Most recent stored row for a series, kept even when it has no value so its diagnostic can be surfaced. */
public record LatestReading(Instant time, AvailabilityStatus status, Double value, String diagnostic) {
}
