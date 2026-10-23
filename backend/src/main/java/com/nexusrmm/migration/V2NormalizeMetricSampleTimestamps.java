package com.nexusrmm.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes {@code metric_sample.timestamp} to a canonical
 * epoch-millisecond representation.
 *
 * <b>Root cause.</b> Hibernate 6 maps {@code java.time.Instant} to
 * {@code SqlTypes.TIMESTAMP_UTC} by default (falling back to plain
 * {@code TIMESTAMP} for a dialect with no timezone-aware SQL type — true
 * of {@code hibernate-community-dialects}' {@code SQLiteDialect}).
 * Combined with v1's table rebuild declaring the column as {@code TEXT}
 * (a reasonable, generic choice at the time, made before this specific
 * temporal-type interaction was understood), Hibernate ended up binding
 * {@code Instant} values through a path that SQLite, under TEXT affinity,
 * stored as the raw text form of a bare epoch-millisecond number for some
 * rows — while sqlite-jdbc's {@code ResultSet.getTimestamp()} only knows
 * how to parse a formatted date-time string, not a bare number, producing
 * "Error parsing time stamp" the moment such a row was read back.
 *
 * <b>The permanent fix</b> (see {@code MetricSample.timestamp} and
 * {@code InstantEpochMillisConverter}) is the same strategy used for
 * metric_type/availability_status: an explicit {@code AttributeConverter}
 * maps the attribute to a plain {@code Long}, so Hibernate's temporal type
 * machinery — {@code TIMESTAMP_UTC}, {@code getTimestamp()}, all of it —
 * is bypassed entirely. Reads/writes go through ordinary
 * {@code setLong()}/{@code getLong()} binding, for which SQLite's dynamic
 * typing correctly handles a numeric value regardless of the column's
 * declared affinity.
 *
 * <b>This migration</b> is the one-time repair for rows already on disk.
 * Every row's current raw value is read as text (via {@code CAST(... AS
 * TEXT)}, which is a no-op for values already stored as TEXT and a clean
 * stringification for any stored as INTEGER) and parsed in Java, trying,
 * in order: a bare epoch-millisecond number (already canonical — the
 * reported defect), a proper ISO-8601 instant string, and sqlite-jdbc's
 * own default {@code PreparedStatement.setTimestamp()} string format
 * ("yyyy-MM-dd HH:mm:ss[.SSS]", space-separated, no zone). Every row must
 * parse under one of these before any write happens — if even one row
 * matches none of them, this migration throws before issuing a single
 * UPDATE, so it fails atomically rather than guessing, discarding, or
 * partially applying.
 *
 * No table rebuild is needed: unlike the CHECK-constraint incident, this
 * is a data problem, not a schema-constraint problem. The column's
 * declared type name is left as {@code TEXT} (harmless — see above); only
 * the values it holds change, via plain {@code UPDATE} statements, which
 * can never alter the row count.
 */
final class V2NormalizeMetricSampleTimestamps implements SchemaMigrator.Migration {

    private static final Logger log = LoggerFactory.getLogger(V2NormalizeMetricSampleTimestamps.class);

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "Normalize metric_sample.timestamp to a canonical epoch-millisecond representation";
    }

    @Override
    public void apply(Connection conn) throws Exception {
        if (!tableExists(conn, "metric_sample")) {
            log.info("No metric_sample table found; nothing to normalize (fresh database).");
            return;
        }

        long before = count(conn, "metric_sample");

        List<long[]> pending = new ArrayList<>(); // { id, canonicalEpochMillis }
        int alreadyNormalized = 0;

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, CAST(timestamp AS TEXT) AS raw_ts FROM metric_sample")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String raw = rs.getString("raw_ts");

                if (isPureEpochMillis(raw)) {
                    alreadyNormalized++;
                    continue;
                }
                // Throws immediately, before any UPDATE is issued, if this
                // row's value doesn't match any known format — see javadoc.
                long epochMillis = parseToEpochMillis(id, raw);
                pending.add(new long[] { id, epochMillis });
            }
        }

        if (pending.isEmpty()) {
            log.info("All {} row(s) in metric_sample already have a canonical epoch-millisecond timestamp; "
                    + "nothing to do.", alreadyNormalized);
            return;
        }

        log.warn("Normalizing {} row(s) in metric_sample with a non-canonical timestamp representation "
                + "({} row(s) already canonical).", pending.size(), alreadyNormalized);

        try (PreparedStatement update = conn.prepareStatement("UPDATE metric_sample SET timestamp = ? WHERE id = ?")) {
            for (long[] row : pending) {
                update.setLong(1, row[1]);
                update.setLong(2, row[0]);
                update.addBatch();
            }
            update.executeBatch();
        }

        long after = count(conn, "metric_sample");
        if (after != before) {
            throw new IllegalStateException(
                    "Row count changed during timestamp normalization (before=" + before + ", after=" + after
                    + "); this should be impossible for an UPDATE-only migration — aborting.");
        }

        log.info("Normalized {} row(s); row count unchanged ({}).", pending.size(), after);
    }

    /** A bare (optionally negative) integer — already-canonical epoch milliseconds. */
    private boolean isPureEpochMillis(String raw) {
        return TimestampNormalizer.isCanonical(raw);
    }

    /**
     * Parsing now lives in {@link TimestampNormalizer} so V2 and V3 share
     * one implementation of the accepted formats and the refuse-to-guess
     * rule. Behavior for every input V2 previously accepted is unchanged;
     * it additionally accepts an integral decimal such as
     * {@code "1758362400000.0"}, which previously aborted the migration.
     */
    private long parseToEpochMillis(long rowId, String raw) {
        return TimestampNormalizer.toEpochMillis("metric_sample.id=" + rowId, raw);
    }

    private boolean tableExists(Connection conn, String tableName) throws Exception {
        try (var ps = conn.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long count(Connection conn, String tableName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM " + tableName)) {
            return rs.next() ? rs.getLong("c") : -1;
        }
    }
}
