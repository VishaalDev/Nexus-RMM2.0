package com.nexusrmm.health.provider;

/**
 * Native SQL used by Module 2's bucketed reads. Kept as constants in one
 * place so the repository annotations and the real-SQLite test
 * ({@code HealthSqlTest}) execute the exact same text.
 *
 * Why native rather than JPQL: bucketing needs integer arithmetic on the
 * timestamp, which JPQL cannot express against an attribute mapped through
 * {@code InstantEpochMillisConverter}. {@code CAST(timestamp AS INTEGER)}
 * works for both on-disk shapes in the wild - INTEGER epoch millis (fresh
 * databases) and TEXT holding digits (databases rebuilt by migration V1,
 * which declared the column TEXT).
 *
 * The null-instance and named-instance variants are separate statements so
 * no query ever binds a null parameter.
 */
public final class HealthSql {

    private HealthSql() {
    }

    private static final String SELECT_BUCKET = """
            SELECT (CAST(timestamp AS INTEGER) / :bucketMs) * :bucketMs AS bucketStartMs,
                   AVG(value)    AS avgValue,
                   MIN(value)    AS minValue,
                   MAX(value)    AS maxValue,
                   COUNT(*)      AS sampleCount,
                   COUNT(value)  AS valueCount
            FROM metric_sample
            WHERE computer_id = :computerId
              AND metric_type = :metricType
            """;

    private static final String RANGE_AND_GROUP = """
              AND CAST(timestamp AS INTEGER) >= :fromMs
              AND CAST(timestamp AS INTEGER) <  :toMs
            GROUP BY bucketStartMs
            ORDER BY bucketStartMs ASC
            """;

    /** Parameters: computerId, metricType, instanceLabel, bucketMs, fromMs, toMs. */
    public static final String BUCKETS_FOR_INSTANCE = SELECT_BUCKET + "  AND instance_label = :instanceLabel\n" + RANGE_AND_GROUP;

    /** Parameters: computerId, metricType, bucketMs, fromMs, toMs. */
    public static final String BUCKETS_FOR_SINGLE_INSTANCE = SELECT_BUCKET + "  AND instance_label IS NULL\n" + RANGE_AND_GROUP;
}
