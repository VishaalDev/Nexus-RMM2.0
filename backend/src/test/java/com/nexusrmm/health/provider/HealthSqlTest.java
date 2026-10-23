package com.nexusrmm.health.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend test area 19: the exact native SQL the repository runs
 * ({@link HealthSql}), executed against real SQLite via sqlite-jdbc. Covers
 * both on-disk shapes of metric_sample.timestamp found in the wild: TEXT
 * holding digits (databases rebuilt by migration V1) and INTEGER (fresh
 * databases created by Hibernate). Hibernate/Spring Data wiring around
 * these statements is NOT exercised here - see HealthRepositoryQueriesTest.
 */
class HealthSqlTest {

    private static final long MIN = 60_000L;
    private static final long BASE = 1_790_000_000_000L - (1_790_000_000_000L % (30 * MIN)); // aligned to a 30-min bucket
    private static final long BUCKET = 30 * MIN;

    private Path db;

    @AfterEach
    void cleanup() throws Exception {
        if (db != null) Files.deleteIfExists(db);
    }

    private Connection open(String timestampDeclaredType, boolean storeAsText) throws Exception {
        db = Files.createTempFile("nexus-rmm-sql-test-", ".db");
        Files.deleteIfExists(db);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE metric_sample (id INTEGER PRIMARY KEY AUTOINCREMENT, computer_id TEXT NOT NULL, "
                    + "metric_type TEXT NOT NULL, instance_label TEXT, timestamp " + timestampDeclaredType + " NOT NULL, "
                    + "value REAL, unit TEXT, availability_status TEXT NOT NULL, source TEXT, diagnostic TEXT)");
        }
        return c;
    }

    private void insert(Connection c, boolean text, String computer, String type, String instance, long ts, Double value, String status) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO metric_sample (computer_id, metric_type, instance_label, timestamp, value, availability_status) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, computer);
            ps.setString(2, type);
            ps.setString(3, instance);
            if (text) ps.setString(4, String.valueOf(ts)); else ps.setLong(4, ts);
            if (value == null) ps.setNull(5, java.sql.Types.REAL); else ps.setDouble(5, value);
            ps.setString(6, status);
            ps.executeUpdate();
        }
    }

    /** Runs a statement containing :named parameters by rewriting them to ? in order of appearance. */
    private List<Map<String, Object>> run(Connection c, String sql, Map<String, Object> params) throws Exception {
        List<String> order = new ArrayList<>();
        Matcher m = Pattern.compile(":([A-Za-z]+)").matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            order.add(m.group(1));
            m.appendReplacement(sb, "?");
        }
        m.appendTail(sb);
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
            for (int i = 0; i < order.size(); i++) {
                Object v = params.get(order.get(i));
                if (v == null) throw new IllegalStateException("missing parameter " + order.get(i));
                ps.setObject(i + 1, v);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
            }
        }
        return out;
    }

    private Map<String, Object> params(String instance, long from, long to) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("computerId", "c1");
        p.put("metricType", "STORAGE_UTILIZATION");
        if (instance != null) p.put("instanceLabel", instance);
        p.put("bucketMs", BUCKET);
        p.put("fromMs", from);
        p.put("toMs", to);
        return p;
    }

    @ParameterizedTest(name = "timestamp column stored as TEXT digits = {0}")
    @ValueSource(booleans = { true, false })
    void aggregatesIntoThirtyMinuteBucketsWithCorrectStatsAndCounts(boolean text) throws Exception {
        try (Connection c = open(text ? "TEXT" : "INTEGER", text)) {
            // bucket 0: values 10, 20, 30 and one UNAVAILABLE (NULL) row; bucket 1: value 50
            insert(c, text, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + 1 * MIN, 10.0, "AVAILABLE");
            insert(c, text, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + 5 * MIN, 20.0, "AVAILABLE");
            insert(c, text, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + 9 * MIN, 30.0, "AVAILABLE");
            insert(c, text, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + 12 * MIN, null, "UNAVAILABLE");
            insert(c, text, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + 31 * MIN, 50.0, "AVAILABLE");

            var rows = run(c, HealthSql.BUCKETS_FOR_INSTANCE, params("C:\\", BASE, BASE + 2 * BUCKET));

            assertEquals(2, rows.size());
            var b0 = rows.get(0);
            assertEquals(BASE, ((Number) b0.get("bucketStartMs")).longValue());
            assertEquals(20.0, ((Number) b0.get("avgValue")).doubleValue(), 1e-9, "the NULL row must not be averaged as 0");
            assertEquals(10.0, ((Number) b0.get("minValue")).doubleValue(), 1e-9);
            assertEquals(30.0, ((Number) b0.get("maxValue")).doubleValue(), 1e-9);
            assertEquals(4, ((Number) b0.get("sampleCount")).intValue());
            assertEquals(3, ((Number) b0.get("valueCount")).intValue());
            assertEquals(BASE + BUCKET, ((Number) rows.get(1).get("bucketStartMs")).longValue());
            assertEquals(50.0, ((Number) rows.get(1).get("avgValue")).doubleValue(), 1e-9);
        }
    }

    @ParameterizedTest(name = "timestamp column stored as TEXT digits = {0}")
    @ValueSource(booleans = { true, false })
    void rangeIsHalfOpenAndOrderedAscending(boolean text) throws Exception {
        try (Connection c = open(text ? "TEXT" : "INTEGER", text)) {
            insert(c, text, "c1", "STORAGE_UTILIZATION", "D:\\", BASE, 1.0, "AVAILABLE");              // == from: included
            insert(c, text, "c1", "STORAGE_UTILIZATION", "D:\\", BASE + BUCKET, 2.0, "AVAILABLE");
            insert(c, text, "c1", "STORAGE_UTILIZATION", "D:\\", BASE + 2 * BUCKET, 3.0, "AVAILABLE"); // == to: excluded
            insert(c, text, "c1", "STORAGE_UTILIZATION", "D:\\", BASE - 1, 9.0, "AVAILABLE");          // before from: excluded

            var rows = run(c, HealthSql.BUCKETS_FOR_INSTANCE, params("D:\\", BASE, BASE + 2 * BUCKET));

            assertEquals(2, rows.size());
            assertTrue(((Number) rows.get(0).get("bucketStartMs")).longValue() < ((Number) rows.get(1).get("bucketStartMs")).longValue());
            assertEquals(1.0, ((Number) rows.get(0).get("avgValue")).doubleValue(), 1e-9);
        }
    }

    @Test
    void namedInstanceNullInstanceMetricTypeAndComputerAreAllIsolated() throws Exception {
        try (Connection c = open("TEXT", true)) {
            insert(c, true, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + MIN, 11.0, "AVAILABLE");
            insert(c, true, "c1", "STORAGE_UTILIZATION", "D:\\", BASE + MIN, 22.0, "AVAILABLE");
            insert(c, true, "c1", "STORAGE_UTILIZATION", null, BASE + MIN, 33.0, "AVAILABLE");
            insert(c, true, "c1", "CPU_UTILIZATION", "C:\\", BASE + MIN, 44.0, "AVAILABLE");
            insert(c, true, "other", "STORAGE_UTILIZATION", "C:\\", BASE + MIN, 55.0, "AVAILABLE");

            var named = run(c, HealthSql.BUCKETS_FOR_INSTANCE, params("C:\\", BASE, BASE + BUCKET));
            assertEquals(1, named.size());
            assertEquals(11.0, ((Number) named.get(0).get("avgValue")).doubleValue(), 1e-9);

            var single = run(c, HealthSql.BUCKETS_FOR_SINGLE_INSTANCE, params(null, BASE, BASE + BUCKET));
            assertEquals(1, single.size());
            assertEquals(33.0, ((Number) single.get(0).get("avgValue")).doubleValue(), 1e-9, "only the NULL-instance row");
        }
    }

    @Test
    void aBucketMadeOnlyOfUnavailableRowsHasZeroValueCountAndNullStats() throws Exception {
        try (Connection c = open("TEXT", true)) {
            insert(c, true, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + MIN, null, "UNAVAILABLE");
            var rows = run(c, HealthSql.BUCKETS_FOR_INSTANCE, params("C:\\", BASE, BASE + BUCKET));
            assertEquals(1, rows.size());
            assertEquals(0, ((Number) rows.get(0).get("valueCount")).intValue());
            assertEquals(null, rows.get(0).get("avgValue"));
        }
    }

    @Test
    void noMatchingRowsYieldsAnEmptyResultNotAnError() throws Exception {
        try (Connection c = open("TEXT", true)) {
            assertTrue(run(c, HealthSql.BUCKETS_FOR_INSTANCE, params("Z:\\", BASE, BASE + BUCKET)).isEmpty());
        }
    }

    @Test
    void aMixedLegacyTextAndIntegerColumnStillBucketsByNumericValue() throws Exception {
        // Defensive: rows written as INTEGER next to TEXT digits (e.g. after a partial upgrade).
        try (Connection c = open("TEXT", true)) {
            insert(c, true, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + MIN, 10.0, "AVAILABLE");
            insert(c, false, "c1", "STORAGE_UTILIZATION", "C:\\", BASE + 2 * MIN, 20.0, "AVAILABLE");
            var rows = run(c, HealthSql.BUCKETS_FOR_INSTANCE, params("C:\\", BASE, BASE + BUCKET));
            assertEquals(1, rows.size());
            assertEquals(15.0, ((Number) rows.get(0).get("avgValue")).doubleValue(), 1e-9);
        }
    }
}
