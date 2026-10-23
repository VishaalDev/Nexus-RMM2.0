package com.nexusrmm.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single, shared implementation of "turn whatever legacy timestamp text is
 * on disk into canonical epoch milliseconds", used by every timestamp
 * migration (V2 for metric_sample, V3 for hardware_event and computer) so
 * the accepted formats and the refuse-to-guess rule cannot drift between
 * them.
 *
 * <b>Why this exists (Part Z timestamp audit).</b> {@code InstantEpochMillisConverter}
 * is {@code autoApply = true}, so EVERY {@code Instant} entity attribute is
 * read with {@code ResultSet.getLong()}. Probed against the project's own
 * sqlite-jdbc 3.45.3.0: {@code getLong()} on a legacy text value such as
 * {@code "2026-09-20 10:00:00.277"} does not throw - it silently returns
 * the leading digits ({@code 2026}), so the entity reads back as
 * {@code 1970-01-01T00:00:02.026Z}. That is strictly worse than the
 * original "Error parsing time stamp" failure, because nothing is
 * reported. V2 repaired metric_sample only; this class is what lets V3
 * apply the identical, strict repair to the other tables that hold an
 * {@code Instant}.
 *
 * Accepted representations, in order: a bare integer (already canonical),
 * an integral decimal such as {@code "1758362400000.0"} (what a REAL value
 * becomes under TEXT affinity), an ISO-8601 instant, and sqlite-jdbc's
 * default {@code setTimestamp} text ({@code "yyyy-MM-dd HH:mm:ss[.SSS]"},
 * no zone, interpreted as UTC). Anything else throws - nothing is ever
 * guessed, discarded, or partially applied.
 */
final class TimestampNormalizer {

    private static final Pattern PURE_INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern INTEGRAL_DECIMAL = Pattern.compile("(-?\\d+)\\.0+");

    /** Plausibility window used only to WARN about suspicious canonical values; never to rewrite them. */
    private static final long PLAUSIBLE_MIN_MS = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli();
    private static final long PLAUSIBLE_MAX_MS = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli();

    private TimestampNormalizer() {
    }

    /** A planned rewrite of one column: rows needing change, and how many were already canonical. */
    static final class Plan {
        final String table;
        final String column;
        final List<long[]> pending = new ArrayList<>(); // { rowid, canonicalEpochMillis }
        int alreadyCanonical;
        int implausible;

        Plan(String table, String column) {
            this.table = table;
            this.column = column;
        }
    }

    /** True for a bare (optionally negative) integer: already canonical epoch milliseconds. */
    static boolean isCanonical(String raw) {
        return raw != null && PURE_INTEGER.matcher(raw).matches();
    }

    /** True when a canonical value falls outside 2000-01-01..2100-01-01 (e.g. epoch SECONDS stored as millis). */
    static boolean isImplausible(long epochMillis) {
        return epochMillis < PLAUSIBLE_MIN_MS || epochMillis >= PLAUSIBLE_MAX_MS;
    }

    /**
     * Parses one raw value. {@code what} identifies the row in error
     * messages (e.g. {@code "metric_sample.id=42"}).
     */
    static long toEpochMillis(String what, String raw) {
        if (raw == null) {
            throw new IllegalStateException(what + " has a NULL timestamp; the column is "
                    + "NOT NULL, so this should not be possible - refusing to guess a value.");
        }
        String trimmed = raw.trim();

        if (isCanonical(trimmed)) {
            return Long.parseLong(trimmed);
        }

        Matcher decimal = INTEGRAL_DECIMAL.matcher(trimmed);
        if (decimal.matches()) {
            return Long.parseLong(decimal.group(1));
        }

        // A proper ISO-8601 instant, e.g. "2026-09-20T10:00:00Z" or "...T10:00:00.277Z".
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // fall through to the next known format
        }

        // sqlite-jdbc's default setTimestamp() text: space-separated, no zone suffix.
        try {
            String normalized = trimmed.replace(' ', 'T');
            if (!normalized.endsWith("Z")) {
                normalized = normalized + "Z";
            }
            return Instant.parse(normalized).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // fall through
        }

        throw new IllegalStateException(what + " has an unrecognized timestamp "
                + "representation ('" + raw + "'); refusing to guess. Migration aborted so no data is altered "
                + "or discarded - inspect this row manually.");
    }

    /**
     * Reads every row of {@code table.column} and plans (without writing
     * anything) what needs to change. Throws on the first unparseable row,
     * i.e. before any caller has issued a single UPDATE.
     */
    static Plan plan(Connection conn, String table, String column) throws Exception {
        Plan plan = new Plan(table, column);
        String sql = "SELECT rowid AS rid, CAST(" + column + " AS TEXT) AS raw_ts FROM " + table;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                long rowid = rs.getLong("rid");
                String raw = rs.getString("raw_ts");
                if (isCanonical(raw)) {
                    plan.alreadyCanonical++;
                    if (isImplausible(Long.parseLong(raw))) {
                        plan.implausible++;
                    }
                    continue;
                }
                plan.pending.add(new long[] { rowid, toEpochMillis(table + ".rowid=" + rowid, raw) });
            }
        }
        return plan;
    }

    /** Applies a previously computed plan with plain UPDATEs (which cannot change the row count). */
    static void apply(Connection conn, Plan plan) throws Exception {
        if (plan.pending.isEmpty()) {
            return;
        }
        String sql = "UPDATE " + plan.table + " SET " + plan.column + " = ? WHERE rowid = ?";
        try (PreparedStatement update = conn.prepareStatement(sql)) {
            for (long[] row : plan.pending) {
                update.setLong(1, row[1]);
                update.setLong(2, row[0]);
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    static boolean tableExists(Connection conn, String table) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static boolean columnExists(Connection conn, String table, String column) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    static long count(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM " + table)) {
            return rs.next() ? rs.getLong("c") : -1;
        }
    }
}
