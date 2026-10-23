package com.nexusrmm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A normalized, hardware-relevant entry from the Windows System event log
 * (WHEA, disk/NTFS, power/kernel, PnP/driver). Only Critical/Error-level
 * entries from a curated provider list are stored — see
 * WindowsEventLogService — never a raw dump of the whole log. The unique
 * constraint on (computerId, providerName, recordId) makes re-polling the
 * same lookback window idempotent: an event already stored is never
 * duplicated.
 */
@Entity
@Table(
    name = "hardware_event",
    uniqueConstraints = @UniqueConstraint(columnNames = {"computer_id", "provider_name", "record_id"}),
    indexes = @Index(name = "idx_hardware_event_time", columnList = "computer_id, occurred_at")
)
public class HardwareEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "computer_id", nullable = false, length = 64)
    private String computerId;

    @Column(name = "windows_event_id")
    private Integer windowsEventId;

    @Column(name = "provider_name", length = 128)
    private String providerName;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "level", length = 24)
    private String level;

    @Column(name = "category", length = 24)
    private String category;

    // Explicit, not just inherited from InstantEpochMillisConverter's
    // autoApply: this column must never fall back to Hibernate's temporal
    // mapping (see that converter's javadoc and migration V3).
    @Convert(converter = InstantEpochMillisConverter.class)
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Convert(converter = InstantEpochMillisConverter.class)
    @Column(name = "stored_at", nullable = false)
    private Instant storedAt;

    /** Truncated, normalized summary — never the full raw log payload. */
    @Column(name = "message", length = 512)
    private String message;

    protected HardwareEvent() {
        // JPA
    }

    public HardwareEvent(String computerId, Integer windowsEventId, String providerName, Long recordId,
                          String level, String category, Instant occurredAt, Instant storedAt, String message) {
        this.computerId = computerId;
        this.windowsEventId = windowsEventId;
        this.providerName = providerName;
        this.recordId = recordId;
        this.level = level;
        this.category = category;
        this.occurredAt = occurredAt;
        this.storedAt = storedAt;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public String getComputerId() {
        return computerId;
    }

    public Integer getWindowsEventId() {
        return windowsEventId;
    }

    public String getProviderName() {
        return providerName;
    }

    public Long getRecordId() {
        return recordId;
    }

    public String getLevel() {
        return level;
    }

    public String getCategory() {
        return category;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getStoredAt() {
        return storedAt;
    }

    public String getMessage() {
        return message;
    }
}
