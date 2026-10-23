package com.nexusrmm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Identity record for the machine being monitored. In this single-agent
 * vertical slice there is exactly one row, generated on first startup and
 * reused thereafter (see DeviceIdentityService). The schema already keys
 * everything else off computerId so multi-device fleet monitoring
 * (roadmap item) can be added later without a migration.
 */
@Entity
@Table(name = "computer")
public class Computer {

    @Id
    @Column(name = "computer_id", nullable = false, updatable = false, length = 64)
    private String computerId;

    @Column(name = "hostname", nullable = false)
    private String hostname;

    @Column(name = "manufacturer")
    private String manufacturer;

    @Column(name = "model")
    private String model;

    @Column(name = "os_name")
    private String osName;

    @Column(name = "os_version")
    private String osVersion;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected Computer() {
        // JPA
    }

    public Computer(String computerId, String hostname, String manufacturer, String model,
                     String osName, String osVersion, Instant firstSeenAt, Instant lastSeenAt) {
        this.computerId = computerId;
        this.hostname = hostname;
        this.manufacturer = manufacturer;
        this.model = model;
        this.osName = osName;
        this.osVersion = osVersion;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

    public String getComputerId() {
        return computerId;
    }

    public String getHostname() {
        return hostname;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public String getModel() {
        return model;
    }

    public String getOsName() {
        return osName;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
