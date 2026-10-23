package com.nexusrmm.entity;

/**
 * Identifies which metric a {@link MetricSample} row holds.
 *
 * Metrics marked "multi-instance" can have several concurrent readings per
 * poll cycle (one per disk, NIC, GPU, etc.) distinguished by
 * MetricSample.instanceLabel; the rest have exactly one instance
 * (instanceLabel is null).
 */
public enum MetricType {
    CPU_UTILIZATION,                 // single-instance
    CPU_TEMPERATURE,                 // single-instance
    RAM_UTILIZATION,                 // single-instance
    STORAGE_UTILIZATION,             // multi-instance: one per volume/mount
    STORAGE_READ_BYTES_PER_SEC,      // multi-instance: one per physical disk
    STORAGE_WRITE_BYTES_PER_SEC,     // multi-instance: one per physical disk
    GPU_UTILIZATION,                 // multi-instance: one per GPU (capability-limited, see GpuMetricsProvider)
    GPU_TEMPERATURE,                 // multi-instance: one per GPU (capability-limited)
    BATTERY_LEVEL,                   // multi-instance: one per power source (NOT_APPLICABLE on desktops)
    NETWORK_RX_BYTES_PER_SEC,        // multi-instance: one per active adapter
    NETWORK_TX_BYTES_PER_SEC         // multi-instance: one per active adapter
}
