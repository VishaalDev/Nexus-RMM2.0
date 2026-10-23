package com.nexusrmm.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;

/**
 * Maps {@link Instant} to/from a plain epoch-millisecond {@link Long}
 * column.
 *
 * This exists for the same class of reason {@code MetricTypeConverter}
 * does. Hibernate 6 maps {@code java.time.Instant} to
 * {@code SqlTypes.TIMESTAMP_UTC} by default (falling back to plain
 * {@code TIMESTAMP} for a dialect with no timezone-aware SQL type — true
 * of {@code hibernate-community-dialects}' {@code SQLiteDialect}). In
 * this project's Hibernate 6.4.4.Final + that dialect + sqlite-jdbc
 * combination, that binding path produced an on-disk value that
 * sqlite-jdbc's own {@code ResultSet.getTimestamp()} could not reliably
 * parse back — surfacing as "Error parsing time stamp" for rows whose
 * stored value was a bare epoch-millisecond number rather than a
 * driver-recognized formatted date-time string.
 *
 * Rather than chase exactly which internal Hibernate/driver code path
 * produced which representation on which write, this converter takes
 * full, explicit, deterministic control — the same strategy already
 * proven for the enum CHECK-constraint incident. Hibernate sees a plain
 * {@code Long} attribute: ordinary integer binding
 * ({@code setLong}/{@code getLong}), with none of the temporal-type
 * machinery involved at all, so {@code getTimestamp()} is never called
 * for this column again.
 *
 * Epoch milliseconds was chosen as the canonical representation over an
 * ISO-8601 string because it sorts correctly under a plain numeric
 * {@code ORDER BY} — {@link Instant#toString()} omits trailing
 * zero fractional digits, which can sort strings out of true chronological
 * order — and has no string-parsing edge cases at all. This is purely an
 * internal storage detail: the API layer still serializes {@code Instant}
 * fields as ISO-8601 in JSON responses (Jackson's JavaTimeModule), since
 * conversion only happens at the JDBC boundary.
 *
 * See com.nexusrmm.migration.V2NormalizeMetricSampleTimestamps for the
 * one-time repair this required on a database with rows in mixed legacy
 * formats, and docs/database.md for the full incident writeup.
 */
@Converter(autoApply = true)
public class InstantEpochMillisConverter implements AttributeConverter<Instant, Long> {

    @Override
    public Long convertToDatabaseColumn(Instant attribute) {
        return attribute == null ? null : attribute.toEpochMilli();
    }

    @Override
    public Instant convertToEntityAttribute(Long dbData) {
        return dbData == null ? null : Instant.ofEpochMilli(dbData);
    }
}
