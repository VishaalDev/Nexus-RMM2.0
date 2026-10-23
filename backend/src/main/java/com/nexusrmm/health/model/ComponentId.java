package com.nexusrmm.health.model;

import java.util.Arrays;

/** The eight components Module 2 analyzes. GPU is deliberately absent: Module 1 reports it UNSUPPORTED. */
public enum ComponentId {
    CPU("CPU"),
    MEMORY("Memory"),
    STORAGE_CAPACITY("Storage capacity"),
    STORAGE_IO("Storage I/O"),
    THERMAL("Thermal"),
    BATTERY("Battery"),
    NETWORK("Network"),
    RELIABILITY("Reliability (hardware events)");

    private final String label;

    ComponentId(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Case-insensitive; accepts '-' for '_' so URLs like storage-capacity work. */
    public static ComponentId parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Component is required. Valid values: " + Arrays.toString(values()));
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown component: '" + raw + "'. Valid values: " + Arrays.toString(values()));
        }
    }
}
