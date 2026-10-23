package com.nexusrmm.service;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.provider.AvailabilityStatus;
import com.nexusrmm.provider.CpuUtilizationProvider;
import com.nexusrmm.provider.MetricReading;
import org.junit.jupiter.api.Test;
import oshi.SystemInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the tick-delta baseline behavior documented on the provider:
 * the very first reading has nothing to diff against and must be reported
 * as UNAVAILABLE (never a fabricated number), while the second reading
 * onward produces a real, bounded percentage.
 *
 * This runs against the real OSHI implementation on whatever machine runs
 * the test (per the project's test-data rule, this is acceptable for a
 * unit test — it is never presented as production telemetry).
 */
class CpuUtilizationProviderTest {

    private CpuUtilizationProvider newProvider() {
        return new CpuUtilizationProvider(new SystemInfo().getHardware());
    }

    @Test
    void firstReadingIsUnavailableBaseline() {
        CpuUtilizationProvider provider = newProvider();

        List<MetricReading> first = provider.collect();

        assertEquals(1, first.size());
        assertEquals(AvailabilityStatus.UNAVAILABLE, first.get(0).availabilityStatus());
        assertNull(first.get(0).value());
        assertNotNull(first.get(0).diagnostic());
    }

    @Test
    void secondReadingProducesBoundedRealValue() throws InterruptedException {
        CpuUtilizationProvider provider = newProvider();

        provider.collect(); // establish baseline
        Thread.sleep(200);  // small real gap between tick snapshots
        MetricReading second = provider.collect().get(0);

        assertEquals(AvailabilityStatus.AVAILABLE, second.availabilityStatus());
        assertNotNull(second.value());
        assertTrue(second.value() >= 0.0 && second.value() <= 100.0,
                "CPU utilization must be a real percentage between 0 and 100, was: " + second.value());
        assertEquals("%", second.unit());
        assertNull(second.instanceLabel(), "CPU utilization is a single-instance metric");
    }

    @Test
    void providerDeclaresItsMetricTypeAndIsAlwaysAvailable() {
        CpuUtilizationProvider provider = newProvider();

        assertTrue(provider.supported());
        assertTrue(provider.available());
        assertEquals(java.util.Set.of(MetricType.CPU_UTILIZATION), provider.providedMetricTypes());
    }
}
