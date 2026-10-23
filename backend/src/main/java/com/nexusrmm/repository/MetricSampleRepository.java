package com.nexusrmm.repository;

import com.nexusrmm.entity.MetricSample;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.provider.HealthSql;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MetricSampleRepository extends JpaRepository<MetricSample, Long> {

    /**
     * Every sample of this metric type within the given lookback window.
     * Used by the query service to derive "current value per instance" by
     * grouping in-memory (SQLite + JPQL don't make "latest per group" a
     * clean single query, and the lookback window here is always small —
     * a couple of collection intervals — so this stays cheap).
     */
    @Query("""
        SELECT s FROM MetricSample s
        WHERE s.computerId = :computerId
          AND s.metricType = :metricType
          AND s.timestamp >= :since
        ORDER BY s.timestamp DESC
    """)
    List<MetricSample> findRecent(@Param("computerId") String computerId,
                                   @Param("metricType") MetricType metricType,
                                   @Param("since") Instant since);

    /**
     * Bounded history for one specific instance (or the single instance,
     * when instanceLabel is null) within a time window. Pageable caps the
     * row count so a large window can never return an unbounded result set.
     */
    @Query("""
        SELECT s FROM MetricSample s
        WHERE s.computerId = :computerId
          AND s.metricType = :metricType
          AND ((:instanceLabel IS NULL AND s.instanceLabel IS NULL) OR s.instanceLabel = :instanceLabel)
          AND s.timestamp >= :from
        ORDER BY s.timestamp ASC
    """)
    List<MetricSample> findHistory(@Param("computerId") String computerId,
                                    @Param("metricType") MetricType metricType,
                                    @Param("instanceLabel") String instanceLabel,
                                    @Param("from") Instant from,
                                    Pageable pageable);

    @Query("""
        SELECT COUNT(s) FROM MetricSample s
        WHERE s.computerId = :computerId
          AND s.metricType = :metricType
          AND ((:instanceLabel IS NULL AND s.instanceLabel IS NULL) OR s.instanceLabel = :instanceLabel)
          AND s.timestamp >= :from
    """)
    long countHistory(@Param("computerId") String computerId,
                       @Param("metricType") MetricType metricType,
                       @Param("instanceLabel") String instanceLabel,
                       @Param("from") Instant from);

    // ---- Module 2 (health analysis) read paths --------------------------------

    /** Distinct non-null instance labels (volumes, disks, adapters, power sources) with data since {@code since}. */
    @Query("""
        SELECT DISTINCT s.instanceLabel FROM MetricSample s
        WHERE s.computerId = :computerId
          AND s.metricType = :metricType
          AND s.timestamp >= :since
          AND s.instanceLabel IS NOT NULL
    """)
    List<String> findDistinctInstanceLabels(@Param("computerId") String computerId,
                                            @Param("metricType") MetricType metricType,
                                            @Param("since") Instant since);

    /** Newest rows first (any availability status); pass a PageRequest of size 1 for "the latest". */
    @Query("""
        SELECT s FROM MetricSample s
        WHERE s.computerId = :computerId
          AND s.metricType = :metricType
          AND ((:instanceLabel IS NULL AND s.instanceLabel IS NULL) OR s.instanceLabel = :instanceLabel)
        ORDER BY s.timestamp DESC
    """)
    List<MetricSample> findLatest(@Param("computerId") String computerId,
                                   @Param("metricType") MetricType metricType,
                                   @Param("instanceLabel") String instanceLabel,
                                   Pageable pageable);

    /** Aggregates for a named instance. Native: bucketing needs integer arithmetic (see HealthSql). */
    @Query(value = HealthSql.BUCKETS_FOR_INSTANCE, nativeQuery = true)
    List<BucketRow> bucketsForInstance(@Param("computerId") String computerId,
                                       @Param("metricType") String metricType,
                                       @Param("instanceLabel") String instanceLabel,
                                       @Param("bucketMs") long bucketMs,
                                       @Param("fromMs") long fromMs,
                                       @Param("toMs") long toMs);

    /** Aggregates for a single-instance metric (instance_label IS NULL). */
    @Query(value = HealthSql.BUCKETS_FOR_SINGLE_INSTANCE, nativeQuery = true)
    List<BucketRow> bucketsForSingleInstance(@Param("computerId") String computerId,
                                             @Param("metricType") String metricType,
                                             @Param("bucketMs") long bucketMs,
                                             @Param("fromMs") long fromMs,
                                             @Param("toMs") long toMs);

    /** Column aliases in HealthSql map to these getters; Number tolerates SQLite's Integer/Long results. */
    interface BucketRow {
        Number getBucketStartMs();

        Number getAvgValue();

        Number getMinValue();

        Number getMaxValue();

        Number getSampleCount();

        Number getValueCount();
    }
}
