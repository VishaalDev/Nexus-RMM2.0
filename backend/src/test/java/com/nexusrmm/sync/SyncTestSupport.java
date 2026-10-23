package com.nexusrmm.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixtures. Everything is local: a temp SQLite file and a JDK
 * HttpServer on 127.0.0.1. No external network is ever touched.
 */
final class SyncTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private SyncTestSupport() {
    }

    // ---- database ------------------------------------------------------------------

    static Path newDbPath() throws IOException {
        Path p = Files.createTempFile("nexus-sync-test-", ".db");
        Files.deleteIfExists(p);
        p.toFile().deleteOnExit();
        return p;
    }

    static DataSource dataSource(Path db) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + db);
        return ds;
    }

    /** Same shape Hibernate generates for the Module 1 entities (epoch-millis timestamps, no CHECK constraints). */
    static void createTelemetryTables(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS metric_sample (id INTEGER PRIMARY KEY, availability_status VARCHAR(24) NOT NULL, "
                    + "computer_id VARCHAR(64) NOT NULL, diagnostic VARCHAR(256), instance_label VARCHAR(128), "
                    + "metric_type VARCHAR(32) NOT NULL, source VARCHAR(64), timestamp BIGINT NOT NULL, unit VARCHAR(16), value FLOAT(53))");
            st.execute("CREATE TABLE IF NOT EXISTS hardware_event (id INTEGER PRIMARY KEY, category VARCHAR(24), computer_id VARCHAR(64) NOT NULL, "
                    + "level VARCHAR(24), message VARCHAR(512), occurred_at BIGINT NOT NULL, provider_name VARCHAR(128), record_id BIGINT, "
                    + "stored_at BIGINT NOT NULL, windows_event_id INTEGER)");
        }
    }

    static void createAll(DataSource ds) throws Exception {
        createTelemetryTables(ds);
        try (Connection c = ds.getConnection()) {
            SyncSchema.apply(c);
        }
    }

    /** Inserts an AVAILABLE sample; returns its id. */
    static long insertSample(DataSource ds, String type, Double value, long tsMs) throws Exception {
        return insertSample(ds, "pc-1", type, null, value, value == null ? "UNAVAILABLE" : "AVAILABLE", tsMs, value == null ? "sensor missing" : null);
    }

    static long insertSample(DataSource ds, String computer, String type, String instance, Double value, String status,
                             long tsMs, String diagnostic) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO metric_sample (availability_status, computer_id, diagnostic, "
                     + "instance_label, metric_type, source, timestamp, unit, value) VALUES (?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, status);
            ps.setString(2, computer);
            ps.setString(3, diagnostic);
            ps.setString(4, instance);
            ps.setString(5, type);
            ps.setString(6, "test");
            ps.setLong(7, tsMs);
            ps.setString(8, value == null ? null : "%");
            if (value == null) {
                ps.setNull(9, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(9, value);
            }
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static void insertSamples(DataSource ds, int count, long startTsMs) throws Exception {
        for (int i = 0; i < count; i++) {
            insertSample(ds, "CPU_UTILIZATION", 10.0 + i, startTsMs + i * 1000L);
        }
    }

    static long insertEvent(DataSource ds, long occurredMs) throws Exception {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO hardware_event (category, computer_id, level, message, occurred_at, "
                     + "provider_name, record_id, stored_at, windows_event_id) VALUES ('DISK','pc-1','Error','disk error',?,?,?,?,?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, occurredMs);
            ps.setString(2, "disk");
            ps.setLong(3, 4242);
            ps.setLong(4, occurredMs + 5);
            ps.setInt(5, 7);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static long scalar(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ---- settings ---------------------------------------------------------------------

    static SyncSettings settings(String url, int batchSize) {
        return SyncSettings.of(url, "secret-token-123", batchSize, Duration.ofSeconds(2), Duration.ofSeconds(3),
                Duration.ofSeconds(5), Duration.ofSeconds(60), 2.0, 50, 5000, true);
    }

    static SyncSettings settingsWithTimeout(String url, Duration requestTimeout) {
        return SyncSettings.of(url, null, 100, Duration.ofSeconds(2), requestTimeout,
                Duration.ofSeconds(5), Duration.ofSeconds(60), 2.0, 50, 5000, true);
    }

    static SyncEngine engine(DataSource ds, SyncSettings s, Clock clock) {
        SyncStore store = new SyncStore(ds, clock);
        return new SyncEngine(store, new HttpSyncClient(s), s, () -> new SyncDevice("pc-1", "TEST-HOST"), clock, 10);
    }

    static int freePortNotListening() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort(); // closed on return: nothing listens, so connections are refused
        }
    }

    // ---- clock ------------------------------------------------------------------------

    static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ---- fake destination (a controlled TEST server, never part of the product) ---------

    /** Behavior of the next request. */
    interface Behavior {
        void handle(HttpExchange ex, JsonNode request, FakeDestination dest) throws IOException;
    }

    static final class FakeDestination implements AutoCloseable {
        final HttpServer server;
        /** Unique record ids the destination has durably "stored" (dedupes by recordId, like a real receiver must). */
        final Set<String> stored = Collections.synchronizedSet(new LinkedHashSet<>());
        final AtomicInteger requests = new AtomicInteger();
        final List<List<String>> receivedIdsPerRequest = new CopyOnWriteArrayList<>();
        final List<String> authHeaders = new CopyOnWriteArrayList<>();
        final List<JsonNode> requestBodies = new CopyOnWriteArrayList<>();
        volatile Behavior behavior = FakeDestination::ackAll;

        FakeDestination() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/ingest", ex -> {
                byte[] raw = ex.getRequestBody().readAllBytes();
                JsonNode body = MAPPER.readTree(raw);
                requests.incrementAndGet();
                authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
                requestBodies.add(body);
                List<String> ids = new ArrayList<>();
                body.get("records").forEach(r -> ids.add(r.get("recordId").asText()));
                receivedIdsPerRequest.add(ids);
                behavior.handle(ex, body, this);
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/ingest";
        }

        void respond(HttpExchange ex, int status, String json) throws IOException {
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                ex.getResponseBody().write(out);
            }
            ex.close();
        }

        static String ackJson(JsonNode request, List<String> acked, String rejectedJson) {
            StringBuilder sb = new StringBuilder("{\"schemaVersion\":1,\"batchId\":\"")
                    .append(request.get("batchId").asText()).append("\",\"acknowledged\":[");
            for (int i = 0; i < acked.size(); i++) {
                sb.append(i == 0 ? "" : ",").append('"').append(acked.get(i)).append('"');
            }
            sb.append("]");
            if (rejectedJson != null) {
                sb.append(",\"rejected\":").append(rejectedJson);
            }
            return sb.append("}").toString();
        }

        /** Stores every record (idempotently) and acknowledges all of them. */
        static void ackAll(HttpExchange ex, JsonNode req, FakeDestination d) throws IOException {
            List<String> ids = new ArrayList<>();
            req.get("records").forEach(r -> ids.add(r.get("recordId").asText()));
            d.stored.addAll(ids);
            d.respond(ex, 200, ackJson(req, ids, null));
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
