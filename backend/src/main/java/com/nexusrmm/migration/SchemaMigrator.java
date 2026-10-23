package com.nexusrmm.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tiny, dependency-free, versioned SQL migration runner for the SQLite
 * database, executed via raw JDBC — deliberately independent of Hibernate
 * — before Spring's ApplicationContext refreshes (see
 * SchemaMigrationInitializer), so a rebuilt table is already correct by
 * the time Hibernate's ddl-auto:update and the JPA repositories touch it.
 *
 * This exists specifically because SQLite cannot ALTER a CHECK constraint
 * in place, and Hibernate's schema-update mode does not attempt to rebuild
 * a table just because a constraint changed. Every future schema change
 * that can't be expressed as a safe ADD COLUMN (constraint changes, column
 * type changes, data reshaping) belongs here as a new migration appended
 * to {@link #migrations()} — never as a hand-edited existing one, so a
 * database that already applied it is never re-run against a changed
 * definition. Applied versions are tracked in the schema_migration table,
 * which is also the project's schema-version record keeping requirement.
 */
public final class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    private SchemaMigrator() {
    }

    public interface Migration {
        int version();

        String description();

        /**
         * Whether there is anything for this migration to act on yet. When
         * false the runner skips it WITHOUT recording it as applied, so it
         * runs on a later start once its target exists (e.g. a table
         * Hibernate has not created yet on a brand-new database).
         */
        default boolean applicable(Connection connection) throws Exception {
            return true;
        }

        /** Perform the migration. Throwing rolls back this migration only; startup continues. */
        void apply(Connection connection) throws Exception;
    }

    public static void migrate(String databasePath) {
        File dbFile = new File(databasePath);
        boolean freshDatabase = !dbFile.exists();

        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            conn.setAutoCommit(false);
            ensureVersionTable(conn);
            // Membership, not MAX(version): a migration skipped as not-yet-
            // applicable must still run later even if a higher version has
            // since been recorded. Identical to the old behavior whenever
            // history is contiguous.
            Set<Integer> appliedVersions = readAppliedVersions(conn);

            for (Migration migration : migrations()) {
                if (appliedVersions.contains(migration.version())) {
                    continue;
                }
                if (!migration.applicable(conn)) {
                    log.info("Schema migration v{} not applicable yet (target tables absent); skipping without recording it.",
                            migration.version());
                    continue;
                }
                log.info("Applying schema migration v{}: {}", migration.version(), migration.description());
                try {
                    migration.apply(conn);
                    recordVersion(conn, migration.version(), migration.description());
                    conn.commit();
                    log.info("Schema migration v{} applied successfully.", migration.version());
                } catch (Exception e) {
                    conn.rollback();
                    log.error("Schema migration v{} FAILED — database left unchanged (SQLite DDL is "
                            + "transactional, so this rollback is safe). Affected tables may continue "
                            + "rejecting writes until this is fixed.", migration.version(), e);
                }
            }
        } catch (Exception e) {
            log.error("Schema migration check could not run at all", e);
        }

        if (freshDatabase) {
            log.info("No existing database at {}; Hibernate will create a fresh schema with no legacy constraints.", databasePath);
        }
    }

    private static void ensureVersionTable(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_migration (
                    version INTEGER PRIMARY KEY,
                    description TEXT NOT NULL,
                    applied_at TEXT NOT NULL
                )
            """);
        }
        conn.commit();
    }

    private static Set<Integer> readAppliedVersions(Connection conn) throws Exception {
        Set<Integer> applied = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_migration")) {
            while (rs.next()) {
                applied.add(rs.getInt("version"));
            }
        }
        return applied;
    }

    private static void recordVersion(Connection conn, int version, String description) throws Exception {
        try (var ps = conn.prepareStatement(
                "INSERT INTO schema_migration (version, description, applied_at) VALUES (?, ?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, description);
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private static List<Migration> migrations() {
        return List.of(
                new V1RebuildMetricSampleWithoutEnumCheckConstraints(),
                new V2NormalizeMetricSampleTimestamps(),
                new V3NormalizeHardwareEventAndComputerTimestamps(),
                new V4CreateSyncQueueTables()
        );
    }
}
