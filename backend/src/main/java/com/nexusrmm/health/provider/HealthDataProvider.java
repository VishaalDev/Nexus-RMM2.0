package com.nexusrmm.health.provider;

import com.nexusrmm.entity.MetricType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Module 2's only view of Module 1's stored telemetry. The analysis and
 * risk engines depend on this interface alone - never on entities,
 * repositories or Spring - so they are unit-testable with an in-memory
 * implementation and the storage layer can change without touching them.
 * The production implementation is
 * {@code com.nexusrmm.health.service.RepositoryHealthDataProvider}.
 *
 * Contract: methods never fabricate data. A series with no rows returns an
 * empty list / Optional.empty(); "instance" is null for single-instance
 * metrics (CPU, RAM, CPU temperature) and the instance label otherwise.
 */
public interface HealthDataProvider {

    String computerId();

    /** Instance labels that have at least one sample of this type since {@code since} (sorted, never null elements). */
    List<String> instances(MetricType type, Instant since);

    /** Raw rows, oldest first, from {@code from} until now, at most {@code limit}. Includes non-AVAILABLE rows. */
    List<SamplePoint> samples(MetricType type, String instance, Instant from, int limit);

    /** Aggregates over [from, to), oldest first, one per non-empty {@code bucket}-sized slot. */
    List<BucketPoint> buckets(MetricType type, String instance, Instant from, Instant to, Duration bucket);

    /** Newest stored row for the series, whatever its availability status. */
    Optional<LatestReading> latest(MetricType type, String instance);

    /** Stored hardware events at or after {@code since}, newest first, at most {@code limit}. */
    List<EventPoint> events(Instant since, int limit);

    EventMonitoring eventMonitoring();
}
