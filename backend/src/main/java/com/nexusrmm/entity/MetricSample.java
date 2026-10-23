package com.nexusrmm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One collected reading for one metric, for one hardware instance, at one
 * point in time.
 *
 * A sample is always written, even when the underlying sensor was
 * unavailable that cycle — {@code value} is null and
 * {@code availabilityStatus} explains why, so history never silently
 * loses a time slot (it shows as a documented gap instead).
 *
 * {@code instanceLabel} distinguishes multiple concurrent instances of the
 * same metric type (drive letters, NIC names, GPU indices); it is null for
 * metrics that only ever have one instance on a machine.
 *
 * {@code metricType} and {@code availabilityStatus} are mapped via
 * {@link MetricTypeConverter} / {@link com.nexusrmm.provider.AvailabilityStatusConverter}
 * — plain JPA AttributeConverters — rather than
 * {@code @Enumerated(EnumType.STRING)}. Hibernate 6 auto-generates a SQL
 * CHECK constraint enumerating every current enum value for
 * {@code @Enumerated(STRING)} columns, which then goes stale (and starts
 * rejecting inserts) the moment the enum gains a new constant, because
 * neither Hibernate's ddl-auto:update nor SQLite itself can alter an
 * existing CHECK constraint in place. An explicit
 * {@code columnDefinition} was tried first to suppress that constraint
 * generation, but was confirmed — by inspecting the actual generated
 * schema — to NOT suppress it under this project's Hibernate 6.4.4.Final +
 * hibernate-community-dialects + SQLiteDialect combination. Converting the
 * attribute to a plain String sidesteps the problem at its root: Hibernate
 * never resolves this column as an enum type in the first place, so the
 * constraint-generation code path that's gated on that resolution never
 * runs. See com.nexusrmm.migration.SchemaMigrator for the one-time repair
 * this required on a database created before this fix, and
 * docs/database.md for the full incident writeup.
 *
 * {@code timestamp} is similarly mapped via {@link InstantEpochMillisConverter}
 * rather than letting Hibernate infer a temporal SQL type for
 * {@code java.time.Instant} — see that converter's javadoc for why:
 * Hibernate 6's default Instant handling produced an on-disk value that
 * sqlite-jdbc could not always reliably parse back, throwing "Error
 * parsing time stamp" for some rows.
 *
 * Timestamps are always stored in UTC; the frontend converts to local time.
 */
@Entity
@Table(
    name = "metric_sample",
    indexes = {
        @Index(name = "idx_metric_sample_lookup",
               columnList = "computer_id, metric_type, instance_label, timestamp")
    }
)
public class MetricSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "computer_id", nullable = false, length = 64)
    private String computerId;

    @Convert(converter = MetricTypeConverter.class)
    @Column(name = "metric_type", nullable = false, length = 32)
    private MetricType metricType;

    /** Null when this metric type only ever has a single instance on the machine. */
    @Column(name = "instance_label", length = 128)
    private String instanceLabel;

    @Convert(converter = InstantEpochMillisConverter.class)
    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    /** Null when the sensor was unavailable this cycle. */
    @Column(name = "value")
    private Double value;

    @Column(name = "unit", length = 16)
    private String unit;

    @Convert(converter = com.nexusrmm.provider.AvailabilityStatusConverter.class)
    @Column(name = "availability_status", nullable = false, length = 24)
    private com.nexusrmm.provider.AvailabilityStatus availabilityStatus;

    @Column(name = "source", length = 64)
    private String source;

    @Column(name = "diagnostic", length = 256)
    private String diagnostic;

    protected MetricSample() {
        // JPA
    }

    public MetricSample(String computerId, MetricType metricType, String instanceLabel, Instant timestamp,
                         Double value, String unit, com.nexusrmm.provider.AvailabilityStatus availabilityStatus,
                         String source, String diagnostic) {
        this.computerId = computerId;
        this.metricType = metricType;
        this.instanceLabel = instanceLabel;
        this.timestamp = timestamp;
        this.value = value;
        this.unit = unit;
        this.availabilityStatus = availabilityStatus;
        this.source = source;
        this.diagnostic = diagnostic;
    }

    public Long getId() {
        return id;
    }

    public String getComputerId() {
        return computerId;
    }

    public MetricType getMetricType() {
        return metricType;
    }

    public String getInstanceLabel() {
        return instanceLabel;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Double getValue() {
        return value;
    }

    public String getUnit() {
        return unit;
    }

    public com.nexusrmm.provider.AvailabilityStatus getAvailabilityStatus() {
        return availabilityStatus;
    }

    public String getSource() {
        return source;
    }

    public String getDiagnostic() {
        return diagnostic;
    }
}
