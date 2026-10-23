package com.nexusrmm.repository;

import com.nexusrmm.entity.MetricSample;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.provider.AvailabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves, against a real Hibernate + hibernate-community-dialects + SQLite
 * setup (not an in-memory stand-in), that:
 *
 * <ul>
 *   <li><b>Test B</b> — a freshly created schema has NO enum-value CHECK
 *       constraint on metric_type or availability_status at all, verified
 *       by inspecting the actual generated DDL in sqlite_master, not by
 *       reading the entity source.</li>
 *   <li><b>Test C</b> — every current MetricType and AvailabilityStatus
 *       value persists and reads back correctly through the real
 *       repository/Hibernate path.</li>
 *   <li><b>Test D</b> — a metric_type value that doesn't correspond to any
 *       current Java enum constant (simulating a future addition, without
 *       touching the real enum) can still be written at the database
 *       layer, proving no future enum growth will ever require a schema
 *       migration again. This is checked exclusively through raw JDBC —
 *       see the note on Test D below for why it must never be visible to
 *       a repository query.</li>
 * </ul>
 *
 * Lifecycle note (Windows file-lock fix, take 2): the first version of
 * this test manually closed the Spring ApplicationContext in @AfterAll to
 * force the pooled SQLite connection closed before deleting the temp file.
 * That fights Spring's TestContext framework, which still expects to own
 * the context's lifecycle afterward — it later logs "ApplicationContext
 * ... is not active" when its own afterTestClass bookkeeping runs against
 * an already-closed context. The correct approach is the opposite: don't
 * touch the context at all, and don't attempt a synchronous, guaranteed
 * delete of a file that Windows may still have locked. The temp file is
 * instead registered with {@code File.deleteOnExit()} at creation time,
 * so cleanup happens at JVM shutdown, after every Spring-managed
 * connection is long gone — no sleep, no polling, no swallowed exception,
 * because no in-test deletion is attempted in the first place.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MetricSampleRepositoryTest {

    private static Path tempDb;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws IOException {
        tempDb = Files.createTempFile("nexus-rmm-repo-test-", ".db");
        Files.deleteIfExists(tempDb); // let SQLite create it fresh
        // Sole cleanup mechanism (see class javadoc): deletion happens at
        // JVM exit, never forced mid-test against a Spring-owned connection.
        tempDb.toFile().deleteOnExit();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDb);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    private MetricSampleRepository repository;

    // ---- Test B ----------------------------------------------------------

    @Test
    void freshSchemaHasNoEnumValueCheckConstraintOnMetricTypeOrAvailabilityStatus() throws Exception {
        // Touch the repository so we know Hibernate has definitely created
        // the schema by this point (it happens eagerly at context startup
        // under ddl-auto, but this also guards against lazy-init surprises).
        repository.count();

        String ddl = readTableDdl("metric_sample");
        assertNotNull(ddl, "metric_sample table should exist after Hibernate schema generation");

        String normalized = ddl.toLowerCase();
        assertFalse(normalized.contains("check"),
                "Fresh schema must contain NO CHECK constraint on metric_sample at all. Actual DDL:\n" + ddl);
    }

    // ---- Test C ----------------------------------------------------------

    @Test
    void everyImplementedMetricTypeCanBePersistedAndRetrieved() {
        Instant now = Instant.now();
        String computerId = "test-computer";

        for (MetricType type : MetricType.values()) {
            MetricSample sample = new MetricSample(
                    computerId,
                    type,
                    "instance-" + type.name(), // exercises instance_label for every type, single- and multi-instance alike
                    now,
                    42.0,
                    "unit",
                    AvailabilityStatus.AVAILABLE,
                    "test-source",
                    null
            );
            MetricSample saved = repository.saveAndFlush(sample);
            assertNotNull(saved.getId(), "Save should assign an id for " + type);
        }

        // findAll() deserializes every row in the table through the
        // MetricTypeConverter. This is only safe because Test D always
        // cleans up its own raw-JDBC row before returning — see Test D's
        // javadoc for why that boundary matters.
        List<MetricSample> all = repository.findAll();
        for (MetricType type : MetricType.values()) {
            boolean present = all.stream().anyMatch(s -> s.getMetricType() == type);
            assertFalse(!present, "Expected a persisted, retrievable sample for " + type);
        }
        assertEquals(MetricType.values().length, all.size());
    }

    @Test
    void everyAvailabilityStatusCanBePersisted() {
        Instant now = Instant.now();

        for (AvailabilityStatus status : AvailabilityStatus.values()) {
            MetricSample sample = new MetricSample(
                    "test-computer",
                    MetricType.CPU_UTILIZATION,
                    null,
                    now,
                    status == AvailabilityStatus.AVAILABLE ? 10.0 : null,
                    status == AvailabilityStatus.AVAILABLE ? "%" : null,
                    status,
                    "test-source",
                    "diagnostic for " + status
            );
            MetricSample saved = repository.saveAndFlush(sample);
            assertNotNull(saved.getId(), "Save should assign an id for status " + status);
            assertEquals(status, saved.getAvailabilityStatus());
        }
    }

    // ---- Test D ------------------------------------------------------------

    /**
     * Proves a database-level property, not an application-level one:
     * "the schema accepts a metric_type value that doesn't correspond to
     * any current Java enum constant, with no migration required" — NOT
     * "current Java/Hibernate code can deserialize an enum value that
     * doesn't exist," which would be wrong and is correctly NOT true here
     * (see MetricTypeConverter, which must keep throwing
     * IllegalArgumentException for exactly this input).
     *
     * Because of that distinction, this test touches the row exclusively
     * through raw JDBC — insert, verify, and unconditionally delete again
     * in a finally block — and never once goes through
     * {@code repository}. A repository query (e.g. findAll(), as Test C
     * uses) hydrating this row would correctly throw, since Hibernate
     * doesn't know this string either; that's correct converter behavior,
     * not a bug, and this test must never leave the row behind for another
     * test to trip over it.
     */
    @Test
    void databaseLayerPlacesNoConstraintOnAFutureMetricTypeValue() throws Exception {
        String futureValue = "FUTURE_METRIC_TYPE_NOT_YET_IMPLEMENTED";

        try {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
                 var ps = conn.prepareStatement("""
                     INSERT INTO metric_sample
                         (computer_id, metric_type, instance_label, timestamp, value, unit, availability_status, source, diagnostic)
                     VALUES (?, ?, NULL, ?, ?, ?, ?, ?, NULL)
                 """)) {
                ps.setString(1, "test-computer");
                ps.setString(2, futureValue);
                ps.setString(3, Instant.now().toString());
                ps.setDouble(4, 1.0);
                ps.setString(5, "unit");
                ps.setString(6, "AVAILABLE");
                ps.setString(7, "test-source");
                assertDoesNotThrow((Executable) ps::executeUpdate,
                        "The database schema must place no constraint on a metric_type value it doesn't yet know about");
            }

            // Verified exclusively through raw JDBC, deliberately never
            // through repository.findAll() or similar — that's the whole
            // point being proven here (see class-level javadoc above).
            String stored = readRawMetricType(futureValue);
            assertEquals(futureValue, stored, "Row must be physically present in the database");

            // The Java layer, unlike the database, still correctly refuses
            // to treat an unrecognized string as a real MetricType —
            // validation lives here now, not in a SQL CHECK list, and must
            // stay exactly this strict.
            assertThrows(IllegalArgumentException.class, () -> MetricType.valueOf(futureValue),
                    "The Java enum correctly has no knowledge of a value that hasn't been added to it yet");
        } finally {
            // Unconditional: runs even if an assertion above failed, so a
            // failure in this test can never leak a poisoned row into
            // Test C (or any other test in this class) that calls
            // repository.findAll().
            deleteRawRow(futureValue);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private String readTableDdl(String tableName) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             var ps = conn.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("sql") : null;
            }
        }
    }

    private String readRawMetricType(String expectedValue) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             var ps = conn.prepareStatement("SELECT metric_type FROM metric_sample WHERE metric_type = ?")) {
            ps.setString(1, expectedValue);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("metric_type") : null;
            }
        }
    }

    private void deleteRawRow(String metricTypeValue) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + tempDb);
             var ps = conn.prepareStatement("DELETE FROM metric_sample WHERE metric_type = ?")) {
            ps.setString(1, metricTypeValue);
            ps.executeUpdate();
        }
    }
}
