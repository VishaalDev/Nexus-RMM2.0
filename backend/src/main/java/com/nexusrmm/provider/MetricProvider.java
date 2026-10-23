package com.nexusrmm.provider;

import com.nexusrmm.entity.MetricType;

import java.util.Set;

/**
 * Contract every hardware/system metric provider implements.
 *
 * This is the extension point Module 1 is built around: adding a new
 * sensor means writing one new class that implements this interface and
 * registering it as a Spring bean — {@link com.nexusrmm.service.MetricCollectorService}
 * automatically picks up every bean of this type. A provider may cover more
 * than one closely-related metric type (e.g. one disk-I/O provider producing
 * both read and write throughput per disk, since both come from the same
 * underlying poll of the same device) — declare all of them in
 * providedMetricTypes() and tag each reading in collect() accordingly.
 */
public interface MetricProvider {

    /** Which metric type(s) this provider produces. Usually one; occasionally a related pair. */
    Set<MetricType> providedMetricTypes();

    /** Whether this platform/build can theoretically support this sensor at all. */
    boolean supported();

    /** Whether this provider currently has a working reading source. */
    boolean available();

    /** Short identifier of where the data comes from, e.g. "OSHI/CentralProcessor". */
    String source();

    /** Human-readable explanation used when supported()/available() is false. */
    String diagnostic();

    /**
     * Perform one real collection. May return zero readings (nothing to
     * report this cycle), one reading (single-instance metric), or several
     * (one per disk/NIC/GPU/etc). Must never fabricate a value.
     */
    java.util.List<MetricReading> collect();
}
