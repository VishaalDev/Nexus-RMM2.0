package com.nexusrmm.health.provider;

import com.nexusrmm.provider.AvailabilityStatus;

import java.time.Instant;

/**
 * One stored sample as the engine sees it. {@code value} is null unless
 * {@code status} is AVAILABLE; the engine never substitutes a number.
 */
public record SamplePoint(Instant time, Double value, AvailabilityStatus status) {
    public boolean hasValue() {
        return status == AvailabilityStatus.AVAILABLE && value != null && !value.isNaN();
    }
}
