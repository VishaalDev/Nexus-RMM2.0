package com.nexusrmm.migration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend test area 18: Part Z timestamp audit. Runs against real SQLite
 * (sqlite-jdbc), exercising {@link TimestampNormalizer} and migration V3
 * through the real {@link SchemaMigrator} runner.
 */
class TimestampAuditMigrationTest {

    private static final long CANONICAL = Instant.parse("2026-09-20T10:00:00.277Z").toEpochMilli();

    private Path tempDb;

    @AfterEach
    void cleanup() throws Exception {
        if (tempDb != null) {
            Files.deleteIfExists(tempDb);
        }
    }

    private Path newDb() throws Exception {
        Path p = Files.createTempFile("nexus-rmm-v3-test-", ".db");
        Files.deleteIfExists(p);
        tempDb = p;
        return p;
    }

    private Connection open() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + tempDb);
    }

    /** Mirrors Hibernate's real DDL for these tables (see the audited development database). */
    private void createHibernateStyleTables(String occurred, String stored, String first, String last) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE hardware_event (
                    id integer, category varchar(24), computer_id varchar(64) not null, level varchar(24),
                    message varchar(512), occurred_at timestamp not null, provider_name varchar(128),
                    record_id bigint, stored_at timestamp not null, windows_event_id integer, primary key (id))""");
            st.execute("""
                CREATE TABLE computer (
                    computer_id varchar(64) not null, first_seen_at timestamp not null, hostname varchar(255) not null,
                    last_seen_at timestamp not null, manufacturer varchar(255), model varchar(255),
                    os_name varchar(255), os_version varchar(255), primary key (computer_id))""");
            st.execute("INSERT INTO hardware_event (id, computer_id, occurred_at, provider_name, record_id, stored_at) VALUES (1,'c1','" + occurred + "','disk',7,'" + stored + "')");
            st.execute("INSERT INTO computer (computer_id, first_seen_at, hostname, last_seen_at) VALUES ('c1','" + first + "','h','" + last + "')");
        }
    }

    private long readAsConverterWould(String table, String column) throws Exception {
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + column + " AS v FROM " + table)) {
            rs.next();
            return rs.getLong("v"); // exactly what InstantEpochMillisConverter's read path does
        }
    }

    private List<Integer> recordedVersions() throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT version FROM schema_migration ORDER BY version")) {
            while (rs.next()) out.add(rs.getInt("version"));
        }
        return out;
    }

    // ---- TimestampNormalizer -------------------------------------------------

    @Test
    void normalizerAcceptsEveryKnownFormatAndAgreesOnTheInstant() {
        assertEquals(CANONICAL, TimestampNormalizer.toEpochMillis("t", String.valueOf(CANONICAL)));
        assertEquals(CANONICAL, TimestampNormalizer.toEpochMillis("t", CANONICAL + ".0"));
        assertEquals(CANONICAL, TimestampNormalizer.toEpochMillis("t", "2026-09-20T10:00:00.277Z"));
        assertEquals(CANONICAL, TimestampNormalizer.toEpochMillis("t", "2026-09-20 10:00:00.277"));
        assertEquals(CANONICAL, TimestampNormalizer.toEpochMillis("t", "  2026-09-20 10:00:00.277  "));
    }

    @Test
    void normalizerRefusesToGuess() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> TimestampNormalizer.toEpochMillis("hardware_event.rowid=3", "yesterday"));
        assertTrue(ex.getMessage().contains("hardware_event.rowid=3") && ex.getMessage().contains("refusing to guess"));
        assertThrows(IllegalStateException.class, () -> TimestampNormalizer.toEpochMillis("t", null));
        assertThrows(IllegalStateException.class, () -> TimestampNormalizer.toEpochMillis("t", "1758362400000.5"),
                "A genuinely fractional millisecond value is not 'integral' and must not be silently truncated");
    }

    @Test
    void plausibilityCheckFlagsEpochSecondsStoredAsMillisWithoutRewritingThem() {
        assertTrue(TimestampNormalizer.isImplausible(1_758_362_400L), "epoch SECONDS read as millis is a 1970 date");
        assertFalse(TimestampNormalizer.isImplausible(CANONICAL));
    }

    // ---- the defect this audit exists for -----------------------------------

    @Test
    void legacyTextReadThroughTheConverterPathSilentlyBecomes1970() throws Exception {
        // Documents the hazard (probed against sqlite-jdbc 3.45.3.0): no exception, wrong instant.
        tempDb = newDb();
        createHibernateStyleTables("2026-09-20 10:00:00.277", "2026-09-20 10:00:01.000",
                String.valueOf(CANONICAL), String.valueOf(CANONICAL));

        long silentlyWrong = readAsConverterWould("hardware_event", "occurred_at");
        assertEquals(Instant.parse("1970-01-01T00:00:02.026Z"), Instant.ofEpochMilli(silentlyWrong));
    }

    // ---- V3 through the real runner -----------------------------------------

    @Test
    void v3RepairsLegacyRowsInBothTablesSoTheConverterReadsTheRightInstant() throws Exception {
        tempDb = newDb();
        createHibernateStyleTables("2026-09-20 10:00:00.277", "2026-09-20T10:00:01.000Z",
                "2026-09-20 10:00:00.277", String.valueOf(CANONICAL));

        SchemaMigrator.migrate(tempDb.toString());

        assertEquals(CANONICAL, readAsConverterWould("hardware_event", "occurred_at"));
        assertEquals(CANONICAL + 723, readAsConverterWould("hardware_event", "stored_at"));
        assertEquals(CANONICAL, readAsConverterWould("computer", "first_seen_at"));
        assertEquals(CANONICAL, readAsConverterWould("computer", "last_seen_at"));
        assertTrue(recordedVersions().contains(3));
    }

    @Test
    void v3LeavesAlreadyCanonicalRowsByteForByteUntouchedAndIsIdempotent() throws Exception {
        tempDb = newDb();
        createHibernateStyleTables(String.valueOf(CANONICAL), String.valueOf(CANONICAL + 1),
                String.valueOf(CANONICAL), String.valueOf(CANONICAL + 2));

        SchemaMigrator.migrate(tempDb.toString());
        SchemaMigrator.migrate(tempDb.toString());

        assertEquals(CANONICAL, readAsConverterWould("hardware_event", "occurred_at"));
        assertEquals(CANONICAL + 1, readAsConverterWould("hardware_event", "stored_at"));
        assertEquals(CANONICAL + 2, readAsConverterWould("computer", "last_seen_at"));
        assertEquals(1, recordedVersions().stream().filter(v -> v == 3).count());
    }

    @Test
    void v3IsAllOrNothingAcrossColumnsWhenOneValueIsUnrecognizable() throws Exception {
        tempDb = newDb();
        // hardware_event is repairable, computer.last_seen_at is garbage: nothing may change anywhere.
        createHibernateStyleTables("2026-09-20 10:00:00.277", "2026-09-20 10:00:01.000",
                String.valueOf(CANONICAL), "not-a-timestamp");

        SchemaMigrator.migrate(tempDb.toString());

        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT occurred_at FROM hardware_event")) {
            rs.next();
            assertEquals("2026-09-20 10:00:00.277", rs.getString(1), "rolled back: the repairable row must be untouched too");
        }
        assertFalse(recordedVersions().contains(3), "a failed migration must not be recorded as applied");
    }

    @Test
    void v3PreservesRowCountsAndNonTimestampColumns() throws Exception {
        tempDb = newDb();
        createHibernateStyleTables("2026-09-20 10:00:00.277", "2026-09-20 10:00:01.000",
                String.valueOf(CANONICAL), String.valueOf(CANONICAL));

        SchemaMigrator.migrate(tempDb.toString());

        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT provider_name, record_id, computer_id FROM hardware_event")) {
            assertTrue(rs.next());
            assertEquals("disk", rs.getString("provider_name"));
            assertEquals(7, rs.getLong("record_id"));
            assertEquals("c1", rs.getString("computer_id"));
            assertFalse(rs.next());
        }
    }

    @Test
    void v3IsSkippedAndNotRecordedWhileNoTargetTableExistsThenAppliesLater() throws Exception {
        tempDb = newDb();
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE metric_sample (id INTEGER PRIMARY KEY AUTOINCREMENT, computer_id TEXT NOT NULL, "
                    + "metric_type TEXT NOT NULL, instance_label TEXT, timestamp TEXT NOT NULL, value REAL, unit TEXT, "
                    + "availability_status TEXT NOT NULL, source TEXT, diagnostic TEXT)");
        }

        SchemaMigrator.migrate(tempDb.toString());
        // v3 must be skipped and unrecorded; Module 3's additive v4 (sync tables) depends on no other table.
        assertEquals(List.of(1, 2, 4), recordedVersions(),
                "v3 must stay unrecorded while its target tables are absent (v4 is Module 3's independent sync-table migration)");

        // Hibernate later creates the tables (on first start after upgrade) -> V3 now applies.
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE computer (computer_id varchar(64) not null, first_seen_at timestamp not null, "
                    + "hostname varchar(255) not null, last_seen_at timestamp not null, primary key (computer_id))");
            st.execute("INSERT INTO computer VALUES ('c1','2026-09-20 10:00:00.277','h','" + CANONICAL + "')");
        }
        SchemaMigrator.migrate(tempDb.toString());

        assertEquals(List.of(1, 2, 3, 4), recordedVersions());
        assertEquals(CANONICAL, readAsConverterWould("computer", "first_seen_at"));
    }

    @Test
    void v2NowAlsoAcceptsAnIntegralDecimalMetricTimestampInsteadOfAborting() throws Exception {
        tempDb = newDb();
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE metric_sample (id INTEGER PRIMARY KEY AUTOINCREMENT, computer_id TEXT NOT NULL, "
                    + "metric_type TEXT NOT NULL, instance_label TEXT, timestamp TEXT NOT NULL, value REAL, unit TEXT, "
                    + "availability_status TEXT NOT NULL, source TEXT, diagnostic TEXT)");
            st.execute("INSERT INTO metric_sample (computer_id, metric_type, timestamp, value, availability_status) "
                    + "VALUES ('c1','CPU_UTILIZATION','" + CANONICAL + ".0', 1.0, 'AVAILABLE')");
        }

        SchemaMigrator.migrate(tempDb.toString());

        try (Connection c = open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT timestamp FROM metric_sample")) {
            rs.next();
            assertEquals(String.valueOf(CANONICAL), rs.getString(1));
        }
        assertTrue(recordedVersions().contains(2));
    }
}
