package com.nexusrmm.service;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.provider.AvailabilityStatus;
import com.nexusrmm.provider.MetricReading;
import com.nexusrmm.provider.RamUtilizationProvider;
import org.junit.jupiter.api.Test;
import oshi.SystemInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RamUtilizationProviderTest {

    @Test
    void producesABoundedRealPercentageImmediately() {
        // Unlike CPU, RAM has no tick-delta warm-up: the very first
        // reading must already be a real, usable value.
        RamUtilizationProvider provider = new RamUtilizationProvider(new SystemInfo().getHardware());

        List<MetricReading> readings = provider.collect();

        assertEquals(1, readings.size());
        MetricReading reading = readings.get(0);
        assertEquals(MetricType.RAM_UTILIZATION, reading.metricType());
        assertEquals(AvailabilityStatus.AVAILABLE, reading.availabilityStatus());
        assertTrue(reading.value() >= 0.0 && reading.value() <= 100.0,
                "RAM utilization must be between 0 and 100, was: " + reading.value());
        assertEquals("%", reading.unit());
    }
}
