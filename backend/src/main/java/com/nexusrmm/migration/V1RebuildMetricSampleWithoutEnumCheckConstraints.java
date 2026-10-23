package com.nexusrmm.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Hibernate 6 auto-generates a CHECK constraint listing every enum
 * constant for {@code @Enumerated(EnumType.STRING)} columns at the moment
 * it first creates a table, and its ddl-auto:update mode never revisits
 * that constraint later — nor could it on SQLite, which has no ALTER TABLE
 * support for modifying constraints at all (SQLite's own documented
 * workaround for any constraint change is: rebuild the table). That is
 * exactly what happened here: {@code metric_sample} was first created with
 * 8 metric types; the enum grew to 11; the CHECK constraint didn't, and
 * started rejecting {@code NETWORK_TX_BYTES_PER_SEC},
 * {@code NETWORK_RX_BYTES_PER_SEC}, {@code STORAGE_WRITE_BYTES_PER_SEC}
 * and {@code STORAGE_READ_BYTES_PER_SEC} with SQLITE_CONSTRAINT_CHECK.
 *
 * This migration rebuilds the table with no CHECK constraint on
 * metric_type or availability_status at all — see MetricTypeConverter and
 * AvailabilityStatusConverter, which stop Hibernate from ever treating
 * these columns as enum-typed (and therefore from ever regenerating a
 * constraint for them) — preserving every existing row. It is idempotent: it inspects the live
 * table definition via sqlite_master and only rebuilds if a CHECK
 * constraint mentioning metric_type is actually present, so it is safe to
 * ship to a database that never had the problem (or has already been
 * fixed).
 */
final class V1RebuildMetricSampleWithoutEnumCheckConstraints implements SchemaMigrator.Migration {

    private static final Logger log = LoggerFactory.getLogger(V1RebuildMetricSampleWithoutEnumCheckConstraints.class);

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "Rebuild metric_sample without Hibernate's auto-generated enum CHECK constraints";
    }

    @Override
    public void apply(Connection conn) throws Exception {
        String existingDdl = readTableDdl(conn, "metric_sample");

        if (existingDdl == null) {
            log.info("No existing metric_sample table found; nothing to migrate (fresh database).");
            return;
        }
        if (!mentionsMetricTypeCheckConstraint(existingDdl)) {
            log.info("Existing metric_sample table has no metric_type CHECK constraint; nothing to migrate.");
            return;
        }

        log.warn("metric_sample has a stale CHECK constraint on metric_type/availability_status from an "
                + "earlier, smaller version of the enum. Rebuilding the table to remove it while preserving "
                + "every existing row.");

        try (Statement st = conn.createStatement()) {
            long before = count(conn, "metric_sample");

            st.execute("ALTER TABLE metric_sample RENAME TO metric_sample_v0_backup");

            // Deliberately permissive SQLite type affinities (TEXT/INTEGER/REAL) —
            // SQLite's manifest typing means the exact declared type is not what
            // enforces correctness here; the absence of a CHECK constraint is.
            st.execute("""
                CREATE TABLE metric_sample (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    computer_id TEXT NOT NULL,
                    metric_type TEXT NOT NULL,
                    instance_label TEXT,
                    timestamp TEXT NOT NULL,
                    value REAL,
                    unit TEXT,
                    availability_status TEXT NOT NULL,
                    source TEXT,
                    diagnostic TEXT
                )
            """);

            // Explicit column list (not SELECT *) so this stays correct even if
            // the backup table's physical column order ever differs.
            st.execute("""
                INSERT INTO metric_sample
                    (id, computer_id, metric_type, instance_label, timestamp, value, unit, availability_status, source, diagnostic)
                SELECT
                    id, computer_id, metric_type, instance_label, timestamp, value, unit, availability_status, source, diagnostic
                FROM metric_sample_v0_backup
            """);

            long after = count(conn, "metric_sample");
            if (after != before) {
                throw new IllegalStateException(
                        "Row count mismatch after migration (before=" + before + ", after=" + after
                        + "); aborting without dropping the backup table so no data is lost.");
            }

            // Drop the backup (and its now-orphaned old index) LAST, before
            // recreating the index under its original name — SQLite index
            // names are unique database-wide, not per-table, so the old
            // index must be gone before the new one can reuse the name.
            st.execute("DROP TABLE metric_sample_v0_backup");

            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_metric_sample_lookup
                ON metric_sample (computer_id, metric_type, instance_label, timestamp)
            """);

            log.info("Rebuilt metric_sample successfully; preserved {} row(s).", after);
        }
    }

    private boolean mentionsMetricTypeCheckConstraint(String ddl) {
        String normalized = ddl.toLowerCase();
        return normalized.contains("check") && normalized.contains("metric_type");
    }

    private String readTableDdl(Connection conn, String tableName) throws Exception {
        try (var ps = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("sql") : null;
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
