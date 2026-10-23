package com.nexusrmm.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the exact reported incident: a metric_sample table created by
 * an earlier version of the app, with a CHECK constraint listing only the
 * original 8 metric types, that rejects the 4 metric types added later
 * (NETWORK_TX/RX_BYTES_PER_SEC, STORAGE_READ/WRITE_BYTES_PER_SEC) with
 * SQLITE_CONSTRAINT_CHECK. Proves SchemaMigrator repairs it in place,
 * without losing the rows that were already there.
 */
class SchemaMigratorTest {

    private Path tempDb;

    @AfterEach
    void cleanup() throws Exception {
        if (tempDb != null) {
            Files.deleteIfExists(tempDb);
        }
    }

    private Path newTempDbPath() throws Exception {
        Path path = Files.createTempFile("nexus-rmm-migration-test-", ".db");
        Files.deleteIfExists(path); // let SQLite create it fresh
        return path;
    }

    /** Builds the exact broken legacy schema this incident report described, with 2 pre-existing rows. */
    private void createLegacySchemaWithTwoRows(Path dbPath) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE metric_sample (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        computer_id TEXT NOT NULL,
                        metric_type TEXT NOT NULL CHECK (metric_type IN (
                            'CPU_UTILIZATION','RAM_UTILIZATION','STORAGE_UTILIZATION','CPU_TEMPERATURE',
                            'GPU_UTILIZATION','GPU_TEMPERATURE','BATTERY_LEVEL','NETWORK_THROUGHPUT'
                        )),
                        instance_label TEXT,
                        timestamp TEXT NOT NULL,
                        value REAL,
                        unit TEXT,
                        availability_status TEXT NOT NULL,
                        source TEXT,
                        diagnostic TEXT
                    )
                """);
                st.execute("""
                    INSERT INTO metric_sample
                        (computer_id, metric_type, instance_label, timestamp, value, unit, availability_status, source, diagnostic)
                    VALUES
                        ('computer-1', 'CPU_UTILIZATION', NULL, '2026-09-20T10:00:00Z', 42.5, '%', 'AVAILABLE', 'test', NULL)
                """);
                st.execute("""
                    INSERT INTO metric_sample
                        (computer_id, metric_type, instance_label, timestamp, value, unit, availability_status, source, diagnostic)
                    VALUES
                        ('computer-1', 'RAM_UTILIZATION', NULL, '2026-09-20T10:00:10Z', 61.2, '%', 'AVAILABLE', 'test', NULL)
                """);
            }
        }
    }

    private String tableDdl(Path dbPath, String table) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var ps = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("sql") : null;
            }
        }
    }

    private void insertRow(Path dbPath, String metricType) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
            st.execute("""
                INSERT INTO metric_sample
                    (computer_id, metric_type, instance_label, timestamp, value, unit, availability_status, source, diagnostic)
                VALUES
                    ('computer-1', '%s', 'eth0', '2026-09-25T00:00:00Z', 123.0, 'B/s', 'AVAILABLE', 'test', NULL)
            """.formatted(metricType));
        }
    }

    @Test
    void legacySchemaActuallyRejectsTheNewMetricTypesBeforeMigrating() throws Exception {
        // Sanity check that this test is reproducing the real reported bug,
        // not a strawman: confirm the unmigrated legacy table really does
        // throw SQLITE_CONSTRAINT_CHECK for the new metric types.
        tempDb = newTempDbPath();
        createLegacySchemaWithTwoRows(tempDb);

        SQLException ex = assertThrows(SQLException.class, () -> insertRow(tempDb, "NETWORK_TX_BYTES_PER_SEC"));
        assertTrue(ex.getMessage().contains("CHECK") || ex.getMessage().contains("constraint"),
                "Expected a CHECK constraint violation, got: " + ex.getMessage());
    }

    @Test
    void migrationRemovesTheConstraintAndPreservesExistingRows() throws Exception {
        tempDb = newTempDbPath();
        createLegacySchemaWithTwoRows(tempDb);

        SchemaMigrator.migrate(tempDb.toString());

        String newDdl = tableDdl(tempDb, "metric_sample");
        assertFalse(newDdl.toUpperCase().contains("CHECK"), "Rebuilt table must have no CHECK constraint: " + newDdl);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM metric_sample")) {
            rs.next();
            assertEquals(2, rs.getInt("c"), "The 2 pre-existing rows must survive the rebuild");
        }
    }

    @Test
    void allFourPreviouslyRejectedMetricTypesCanBeInsertedAfterMigration() throws Exception {
        tempDb = newTempDbPath();
        createLegacySchemaWithTwoRows(tempDb);

        SchemaMigrator.migrate(tempDb.toString());

        assertDoesNotThrow(() -> insertRow(tempDb, "NETWORK_TX_BYTES_PER_SEC"));
        assertDoesNotThrow(() -> insertRow(tempDb, "NETWORK_RX_BYTES_PER_SEC"));
        assertDoesNotThrow(() -> insertRow(tempDb, "STORAGE_READ_BYTES_PER_SEC"));
        assertDoesNotThrow(() -> insertRow(tempDb, "STORAGE_WRITE_BYTES_PER_SEC"));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM metric_sample")) {
            rs.next();
            assertEquals(6, rs.getInt("c"), "2 original rows + 4 newly inserted rows");
        }
    }

    @Test
    void migrationIsIdempotentAndSafeToRunTwice() throws Exception {
        tempDb = newTempDbPath();
        createLegacySchemaWithTwoRows(tempDb);

        SchemaMigrator.migrate(tempDb.toString());
        assertDoesNotThrow(() -> SchemaMigrator.migrate(tempDb.toString()));

        // Second run must not have rebuilt again / duplicated data.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM metric_sample")) {
            rs.next();
            assertEquals(2, rs.getInt("c"));
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) AS v FROM schema_migration")) {
            rs.next();
            // Written when v1 was the only migration; v2 (timestamp
            // normalization) now legitimately also applies against these
            // seeded rows (their timestamps are ISO-8601 strings, which v2
            // correctly normalizes), so the real post-migrate() max version
            // is 2, not 1. Not a migration bug — this expectation was just
            // never updated when v2 was added. Module 3's additive v4
            // (sync queue tables, applicable to any database) now applies
            // too, so the max recorded version is 4.
            assertEquals(4, rs.getInt("v"));
        }
    }

    @Test
    void freshDatabaseIsANoOp() throws Exception {
        tempDb = newTempDbPath();
        // No table created at all — simulates a brand-new install.

        assertDoesNotThrow(() -> SchemaMigrator.migrate(tempDb.toString()));

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             var ps = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name='metric_sample'");
             ResultSet rs = ps.executeQuery()) {
            assertFalse(rs.next(), "Migration must not create metric_sample itself — that's Hibernate's job");
        }
    }

    // =====================================================================
    // v2: timestamp normalization
    // =====================================================================

    /**
     * Builds a table already in the post-v1 shape (no CHECK constraint —
     * that incident is fixed and out of scope here) but with rows whose
     * timestamp column holds whatever raw values the caller supplies, in
     * whatever mixture of formats. Isolates v2's behavior from v1's.
     */
    private void createSchemaWithTimestamps(Path dbPath, String... timestampValues) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (Statement st = conn.createStatement()) {
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
            }
            try (var ps = conn.prepareStatement("""
                INSERT INTO metric_sample
                    (computer_id, metric_type, instance_label, timestamp, value, unit, availability_status, source, diagnostic)
                VALUES ('computer-1', 'CPU_UTILIZATION', 'instance-a', ?, 10.0, '%', 'AVAILABLE', 'test', NULL)
            """)) {
                for (String ts : timestampValues) {
                    ps.setString(1, ts);
                    ps.executeUpdate();
                }
            }
        }
    }

    private java.util.List<String> readAllRawTimestamps(Path dbPath) throws Exception {
        java.util.List<String> result = new java.util.ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT CAST(timestamp AS TEXT) AS t FROM metric_sample ORDER BY id")) {
            while (rs.next()) {
                result.add(rs.getString("t"));
            }
        }
        return result;
    }

    @Test
    void isoTimestampLegacyRowIsNormalizedToEpochMillis() throws Exception {
        tempDb = newTempDbPath();
        createSchemaWithTimestamps(tempDb, "2026-09-20T10:00:00Z");

        SchemaMigrator.migrate(tempDb.toString());

        java.util.List<String> raw = readAllRawTimestamps(tempDb);
        assertEquals(1, raw.size());
        assertTrue(raw.get(0).matches("-?\\d+"), "Expected a canonical epoch-millisecond value, got: " + raw.get(0));
        assertEquals(java.time.Instant.parse("2026-09-20T10:00:00Z").toEpochMilli(), Long.parseLong(raw.get(0)));
    }

    @Test
    void epochMillisecondLegacyRowIsThisExactReportedDefectAndIsLeftNumericallyUnchanged() throws Exception {
        tempDb = newTempDbPath();
        String original = "1790438061277"; // the exact value from the incident report
        createSchemaWithTimestamps(tempDb, original);

        SchemaMigrator.migrate(tempDb.toString());

        java.util.List<String> raw = readAllRawTimestamps(tempDb);
        assertEquals(1, raw.size());
        assertEquals(Long.parseLong(original), Long.parseLong(raw.get(0)),
                "An already-canonical epoch-millisecond value must round-trip unchanged");
    }

    @Test
    void mixedTimestampRepresentationsInOneDatabaseAreAllNormalized() throws Exception {
        tempDb = newTempDbPath();
        createSchemaWithTimestamps(tempDb,
                "2026-09-20T10:00:00Z",         // ISO-8601
                "1790438061277",                 // bare epoch millis (the reported defect)
                "2026-09-20 10:00:05.500"          // sqlite-jdbc's own default driver string format
        );

        SchemaMigrator.migrate(tempDb.toString());

        java.util.List<String> raw = readAllRawTimestamps(tempDb);
        assertEquals(3, raw.size());
        for (String value : raw) {
            assertTrue(value.matches("-?\\d+"), "Every row must end up canonical epoch millis, got: " + value);
        }
    }

    @Test
    void migrationPreservesRowCountAndEveryNonTimestampColumn() throws Exception {
        tempDb = newTempDbPath();
        createSchemaWithTimestamps(tempDb, "2026-09-20T10:00:00Z", "1790438061277");

        SchemaMigrator.migrate(tempDb.toString());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) AS c FROM metric_sample WHERE computer_id='computer-1' "
                   + "AND metric_type='CPU_UTILIZATION' AND instance_label='instance-a' AND value=10.0 "
                   + "AND unit='%' AND availability_status='AVAILABLE' AND source='test' AND diagnostic IS NULL")) {
            rs.next();
            assertEquals(2, rs.getInt("c"),
                    "Every non-timestamp column must be untouched — only the timestamp representation changes");
        }
    }

    @Test
    void runningTimestampMigrationTwiceIsSafe() throws Exception {
        tempDb = newTempDbPath();
        createSchemaWithTimestamps(tempDb, "2026-09-20T10:00:00Z", "1790438061277");

        SchemaMigrator.migrate(tempDb.toString());
        java.util.List<String> firstPass = readAllRawTimestamps(tempDb);

        assertDoesNotThrow(() -> SchemaMigrator.migrate(tempDb.toString()));
        java.util.List<String> secondPass = readAllRawTimestamps(tempDb);

        assertEquals(firstPass, secondPass, "A second migration run must not change already-canonical values");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) AS v FROM schema_migration")) {
            rs.next();
            // v1, v2 plus Module 3's additive v4 (v3 needs tables this fixture lacks).
            assertEquals(4, rs.getInt("v"));
        }
    }

    @Test
    void anUnrecognizedTimestampRepresentationFailsLoudlyRatherThanBeingGuessedOrDiscarded() throws Exception {
        tempDb = newTempDbPath();
        createSchemaWithTimestamps(tempDb, "not-a-timestamp-at-all");

        // migrate() itself never throws — a failing migration is logged
        // and rolled back internally (see SchemaMigrator) so one broken
        // migration can never crash the whole application at startup.
        SchemaMigrator.migrate(tempDb.toString());

        // The row must be completely untouched — nothing guessed, nothing
        // discarded — because the migration rolled back before writing.
        java.util.List<String> raw = readAllRawTimestamps(tempDb);
        assertEquals(1, raw.size());
        assertEquals("not-a-timestamp-at-all", raw.get(0));

        // And v2 must not be recorded as applied, since it failed — so a
        // future run (once the bad row is fixed some other way) will retry it.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM schema_migration WHERE version = 2")) {
            rs.next();
            assertEquals(0, rs.getInt("c"), "A failed migration must not be recorded as applied");
        }
    }
}
