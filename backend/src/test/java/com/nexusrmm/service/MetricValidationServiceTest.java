package com.nexusrmm.service;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.provider.AvailabilityStatus;
import com.nexusrmm.provider.MetricReading;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MetricValidationServiceTest {

    private final MetricValidationService validator = new MetricValidationService();

    @Test
    void passesThroughAPlausibleReadingUnchanged() {
        MetricReading reading = MetricReading.available(MetricType.CPU_UTILIZATION, null, 42.5, "%", "test");

        MetricReading result = validator.validate(reading);

        assertSame(reading, result);
    }

    @Test
    void rejectsAnOutOfRangePercentageInsteadOfStoringIt() {
        // A provider bug or platform quirk claiming 137% CPU utilization
        // must never be persisted as if it were real telemetry.
        MetricReading bad = MetricReading.available(MetricType.CPU_UTILIZATION, null, 137.0, "%", "test");

        MetricReading result = validator.validate(bad);

        assertEquals(AvailabilityStatus.ERROR, result.availabilityStatus());
        assertNull(result.value());
        assertEquals("test", result.source());
    }

    @Test
    void rejectsAnImplausibleTemperature() {
        MetricReading bad = MetricReading.available(MetricType.CPU_TEMPERATURE, null, 400.0, "\u00B0C", "test");

        MetricReading result = validator.validate(bad);

        assertEquals(AvailabilityStatus.ERROR, result.availabilityStatus());
    }

    @Test
    void passesThroughNonAvailableReadingsWithoutTouchingThem() {
        // Nothing to validate about an honestly-absent value.
        MetricReading unavailable = MetricReading.unavailable(MetricType.CPU_TEMPERATURE, null, "test", "no sensor");

        MetricReading result = validator.validate(unavailable);

        assertSame(unavailable, result);
    }
}
