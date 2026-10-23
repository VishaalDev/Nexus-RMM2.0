package com.nexusrmm.migration;

import com.nexusrmm.entity.MetricSample;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.repository.MetricSampleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves requirement #8 end to end: a real legacy database — seeded with
 * the exact mixed timestamp formats this incident was reported with (a
 * bare epoch-millisecond value and a proper ISO-8601 string) — is migrated
 * automatically via {@link SchemaMigrationInitializer}, the same
 * initializer the production application registers in
 * {@code NexusRmmApplication.main()} (not called directly, as
 * {@code SchemaMigratorTest} does for unit-level coverage), and the
 * resulting rows are then read back successfully through the real
 * repository/Hibernate/JPA path.
 *
 * This is deliberately the exact call path traced in the incident report:
 * {@code MetricQueryService.currentValues()} and
 * {@code .history()} both ultimately call
 * {@code MetricSampleRepository.findRecent()} / {@code findHistory()},
 * which is what threw "Error parsing time stamp." {@code findAll()} here
 * exercises the same underlying Hibernate row-hydration path.
 *
 * Full HTTP-level verification of {@code GET /api/metrics/current} and
 * {@code GET /api/metrics/history} returning 200 requires a running
 * application (this project's sandbox cannot run one — see
 * docs/database.md) and is the runtime verification step, not something
 * this slice test substitutes for.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(initializers = SchemaMigrationInitializer.class)
class TimestampMigrationRepositoryTest {

    private static Path tempDb;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws Exception {
        tempDb = Files.createTempFile("nexus-rmm-ts-migration-test-", ".db");
        Files.deleteIfExists(tempDb);
        tempDb.toFile().deleteOnExit(); // see MetricSampleRepositoryTest for why this, not a forced in-test delete

        seedLegacyDatabaseWithMixedTimestampFormats(tempDb);

        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDb);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        // Critical: SchemaMigrationInitializer reads nexus.database.path to
        // decide which file to migrate. Without this, it defaults to
        // ./data/nexus-rmm.db — a completely different file than the one
        // just seeded above and the one spring.datasource.url points at —
        // so the migration would silently run against the wrong (or the
        // real application's) database, never touching these seeded rows
        // at all, while Hibernate reads the still-raw, un-migrated legacy
        // value from tempDb.
        registry.add("nexus.database.path", () -> tempDb.toString());
    }

    /**
     * Builds the table in the exact shape the reported incident occurred
     * in: no enum CHECK constraint (that incident is already fixed and out
     * of scope here) but with the timestamp column holding the two mixed
     * representations from the report — one proper ISO-8601 string and one
     * bare epoch-millisecond number.
     */
    private static void seedLegacyDatabaseWithMixedTimestampFormats(Path dbPath) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement()) {
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
                    ('computer-1', 'RAM_UTILIZATION', NULL, '1790438061277', 61.2, '%', 'AVAILABLE', 'test', NULL)
            """);
        }
    }

    @Autowired
    private MetricSampleRepository repository;

    @Test
    void migratedLegacyRowsReadSuccessfullyThroughRealRepositoryAndHibernate() throws Exception {
        // Verify the migration's own output directly via JDBC first,
        // independent of Hibernate/JPA entirely. This is the ground truth:
        // if this is wrong, the bug is in the migration; if this is right
        // but the JPA read below is wrong, the bug is elsewhere (e.g. the
        // AttributeConverter, or test wiring pointing Hibernate at the
        // wrong database — which is exactly what caused this incident:
        // the migration was never running against this file at all).
        long expectedIsoMillis = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli();
        long expectedEpochMillis = 1790438061277L;

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT metric_type, CAST(timestamp AS TEXT) AS t FROM metric_sample ORDER BY id")) {
            assertTrue(rs.next(), "Expected the ISO-format row");
            assertEquals("CPU_UTILIZATION", rs.getString("metric_type"));
            assertEquals(String.valueOf(expectedIsoMillis), rs.getString("t"),
                    "The migration must write the COMPLETE epoch-millisecond value, not a truncated/partial one "
                    + "(e.g. SQLite's own TEXT-to-INTEGER coercion taking just a leading numeric prefix, which is "
                    + "exactly what happens if this row is left un-migrated as raw text)");

            assertTrue(rs.next(), "Expected the epoch-millis row");
            assertEquals("RAM_UTILIZATION", rs.getString("metric_type"));
            assertEquals(String.valueOf(expectedEpochMillis), rs.getString("t"),
                    "An already-canonical epoch-millisecond value must round-trip unchanged");
        }

        // Now confirm the real repository/Hibernate path — the exact call
        // the incident traced (MetricQueryService -> repository ->
        // Hibernate -> ResultSet -> "Error parsing time stamp") — reads
        // the same, correct values back.
        List<MetricSample> all = repository.findAll();

        assertEquals(2, all.size(), "Both legacy rows must survive migration and be readable");

        boolean sawIsoRow = all.stream().anyMatch(s -> s.getMetricType() == MetricType.CPU_UTILIZATION);
        boolean sawEpochRow = all.stream().anyMatch(s -> s.getMetricType() == MetricType.RAM_UTILIZATION);
        assertTrue(sawIsoRow, "The originally ISO-formatted legacy row must be readable");
        assertTrue(sawEpochRow, "The originally bare-epoch-millisecond legacy row (the reported defect) must be readable");

        MetricSample epochRow = all.stream()
                .filter(s -> s.getMetricType() == MetricType.RAM_UTILIZATION)
                .findFirst().orElseThrow();
        assertEquals(Instant.ofEpochMilli(expectedEpochMillis), epochRow.getTimestamp(),
                "The timestamp must round-trip to the exact same instant, not just avoid throwing");

        MetricSample isoRow = all.stream()
                .filter(s -> s.getMetricType() == MetricType.CPU_UTILIZATION)
                .findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), isoRow.getTimestamp(),
                "The full instant must be preserved — not truncated to a partial numeric prefix");
    }
}
