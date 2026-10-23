package com.nexusrmm.sync;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable synchronization queue on top of the existing SQLite database
 * (plain JDBC, independent of Hibernate).
 *
 * <p><b>Model.</b> Telemetry stays in {@code metric_sample} /
 * {@code hardware_event}, untouched. {@code sync_outbox} holds one small row
 * per record that is (or was) awaiting delivery: PENDING until the
 * destination acknowledges it (SYNCED) or explicitly refuses it for good
 * (FAILED). A per-table watermark ({@code cursor.*} in {@code sync_state})
 * remembers the highest source row id already queued, so every stored row
 * - including history that existed before Module 3 - is queued exactly
 * once, and the collector never has to know the queue exists.
 *
 * <p>All state is in SQLite, so the queue survives restarts and crashes.
 */
public final class SyncStore {

    public enum RecordType {
        METRIC_SAMPLE("metric_sample"),
        HARDWARE_EVENT("hardware_event");

        private final String table;

        RecordType(String table) {
            this.table = table;
        }

        public String table() {
            return table;
        }
    }

    public static final String PENDING = "PENDING";
    public static final String SYNCED = "SYNCED";
    public static final String FAILED = "FAILED";

    /** A queue row. */
    public record OutboxEntry(long id, RecordType type, long sourceId, int attempts) {
    }

    /** Result of loading payloads: deliverable records plus entries that cannot be delivered, with reasons. */
    public record LoadResult(List<SyncRecord> records, Map<Long, String> unsendable) {
    }

    public record TypeCounts(long pending, long synced, long failed) {
    }

    public record QueueCounts(long pending, long synced, long failed, Map<String, TypeCounts> byType) {
        public long total() {
            return pending + synced + failed;
        }
    }

    private final DataSource dataSource;
    private final Clock clock;

    public SyncStore(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    // ---- setup ---------------------------------------------------------------

    /** Idempotent; the same DDL as migration V4. */
    public void ensureSchema() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            SyncSchema.apply(c);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    /**
     * On first ever run, decides where the watermark starts. With
     * {@code backfill} the whole existing history is eligible (watermark 0);
     * without it only rows stored from now on are. Never moves an existing watermark.
     */
    public void initializeCursors(boolean backfill) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            for (RecordType type : RecordType.values()) {
                if (getState(c, cursorKey(type)) != null) {
                    continue;
                }
                long start = 0;
                if (!backfill && tableExists(c, type.table())) {
                    start = scalarLong(c, "SELECT COALESCE(MAX(id), 0) FROM " + type.table());
                }
                putState(c, cursorKey(type), Long.toString(start));
            }
        }
    }

    // ---- enqueue ---------------------------------------------------------------

    /**
     * Queues up to {@code chunk} not-yet-queued rows of every source table, in
     * id order, each chunk in one short write transaction (so the collector's
     * own writes are never blocked for long).
     *
     * @return number of rows newly queued
     */
    public int enqueueNew(int chunk) throws SQLException {
        int total = 0;
        try (Connection c = dataSource.getConnection()) {
            for (RecordType type : RecordType.values()) {
                if (!tableExists(c, type.table())) {
                    continue;
                }
                total += inImmediateTransaction(c, () -> enqueueChunk(c, type, chunk));
            }
        }
        return total;
    }

    private int enqueueChunk(Connection c, RecordType type, int chunk) throws SQLException {
        long cursor = parseLong(getState(c, cursorKey(type)), 0);
        Long maxId;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(id) FROM (SELECT id FROM " + type.table() + " WHERE id > ? ORDER BY id LIMIT ?)")) {
            ps.setLong(1, cursor);
            ps.setInt(2, chunk);
            try (ResultSet rs = ps.executeQuery()) {
                maxId = rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null;
            }
        }
        if (maxId == null) {
            return 0;
        }
        int inserted;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO sync_outbox (record_type, source_id, status, attempts, enqueued_at) "
                        + "SELECT ?, id, 'PENDING', 0, ? FROM " + type.table() + " WHERE id > ? AND id <= ?")) {
            ps.setString(1, type.name());
            ps.setLong(2, clock.millis());
            ps.setLong(3, cursor);
            ps.setLong(4, maxId);
            inserted = ps.executeUpdate();
        }
        putState(c, cursorKey(type), Long.toString(maxId));
        return inserted;
    }

    // ---- read the queue ----------------------------------------------------------

    /** PENDING entries in queue order with outbox id greater than {@code afterOutboxId}. */
    public List<OutboxEntry> nextPending(long afterOutboxId, int limit) throws SQLException {
        List<OutboxEntry> entries = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, record_type, source_id, attempts FROM sync_outbox "
                             + "WHERE status = 'PENDING' AND id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, afterOutboxId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new OutboxEntry(rs.getLong(1), RecordType.valueOf(rs.getString(2)),
                            rs.getLong(3), rs.getInt(4)));
                }
            }
        }
        return entries;
    }

    /**
     * Loads the original stored values for queue entries. Values are copied
     * verbatim: SQL NULL becomes JSON null (an unavailable reading), a real
     * 0.0 stays 0.0, and the stored timestamp is sent both as ISO-8601 and as
     * epoch milliseconds. Rows that cannot be represented faithfully are
     * returned in {@code unsendable} instead of being altered.
     */
    public LoadResult loadRecords(List<OutboxEntry> entries) throws SQLException {
        List<SyncRecord> records = new ArrayList<>();
        Map<Long, String> unsendable = new LinkedHashMap<>();
        Map<RecordType, List<OutboxEntry>> byType = new LinkedHashMap<>();
        for (OutboxEntry e : entries) {
            byType.computeIfAbsent(e.type(), k -> new ArrayList<>()).add(e);
        }
        try (Connection c = dataSource.getConnection()) {
            for (Map.Entry<RecordType, List<OutboxEntry>> group : byType.entrySet()) {
                Map<Long, SyncRecord> loaded = new LinkedHashMap<>();
                Map<Long, String> problems = new LinkedHashMap<>();
                if (group.getKey() == RecordType.METRIC_SAMPLE) {
                    loadMetricSamples(c, group.getValue(), loaded, problems);
                } else {
                    loadHardwareEvents(c, group.getValue(), loaded, problems);
                }
                for (OutboxEntry e : group.getValue()) {
                    SyncRecord r = loaded.get(e.id());
                    if (r != null) {
                        records.add(r);
                    } else {
                        unsendable.put(e.id(), problems.getOrDefault(e.id(),
                                "Source row " + group.getKey().table() + "#" + e.sourceId() + " no longer exists"));
                    }
                }
            }
        }
        records.sort((a, b) -> Long.compare(a.outboxId(), b.outboxId()));
        return new LoadResult(records, unsendable);
    }

    private void loadMetricSamples(Connection c, List<OutboxEntry> group, Map<Long, SyncRecord> out,
                                   Map<Long, String> problems) throws SQLException {
        Map<Long, OutboxEntry> bySource = indexBySource(group);
        String sql = "SELECT id, computer_id, metric_type, instance_label, timestamp, value, unit, "
                + "availability_status, source, diagnostic FROM metric_sample WHERE id IN (" + placeholders(group.size()) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    OutboxEntry entry = bySource.get(id);
                    Object ts = rs.getObject("timestamp");
                    if (!(ts instanceof Number) || ((Number) ts).longValue() <= 0) {
                        problems.put(entry.id(), "Stored timestamp is not canonical epoch milliseconds; "
                                + "it was not sent (sending it would misstate the original time)");
                        continue;
                    }
                    long tsMs = ((Number) ts).longValue();
                    Double value = null;
                    double raw = rs.getDouble("value");
                    if (!rs.wasNull()) {
                        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                            problems.put(entry.id(), "Stored value is not a finite number; not sent");
                            continue;
                        }
                        value = raw;
                    }
                    String computerId = rs.getString("computer_id");
                    ObjectNode p = JsonNodeFactory.instance.objectNode();
                    p.put("computerId", computerId);
                    p.put("metricType", rs.getString("metric_type"));
                    putText(p, "instanceLabel", rs.getString("instance_label"));
                    p.put("timestamp", Instant.ofEpochMilli(tsMs).toString());
                    p.put("timestampEpochMs", tsMs);
                    if (value == null) {
                        p.putNull("value");
                    } else {
                        p.put("value", value.doubleValue());
                    }
                    putText(p, "unit", rs.getString("unit"));
                    p.put("availabilityStatus", rs.getString("availability_status"));
                    putText(p, "source", rs.getString("source"));
                    putText(p, "diagnostic", rs.getString("diagnostic"));
                    out.put(entry.id(), new SyncRecord(entry.id(), recordId(computerId, RecordType.METRIC_SAMPLE, id),
                            RecordType.METRIC_SAMPLE.name(), p));
                }
            }
        }
    }

    private void loadHardwareEvents(Connection c, List<OutboxEntry> group, Map<Long, SyncRecord> out,
                                    Map<Long, String> problems) throws SQLException {
        Map<Long, OutboxEntry> bySource = indexBySource(group);
        String sql = "SELECT id, computer_id, windows_event_id, provider_name, record_id, level, category, "
                + "occurred_at, stored_at, message FROM hardware_event WHERE id IN (" + placeholders(group.size()) + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, group);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    OutboxEntry entry = bySource.get(id);
                    Object occurred = rs.getObject("occurred_at");
                    Object stored = rs.getObject("stored_at");
                    if (!(occurred instanceof Number) || ((Number) occurred).longValue() <= 0
                            || !(stored instanceof Number) || ((Number) stored).longValue() <= 0) {
                        problems.put(entry.id(), "Stored timestamp is not canonical epoch milliseconds; not sent");
                        continue;
                    }
                    long occurredMs = ((Number) occurred).longValue();
                    long storedMs = ((Number) stored).longValue();
                    String computerId = rs.getString("computer_id");
                    ObjectNode p = JsonNodeFactory.instance.objectNode();
                    p.put("computerId", computerId);
                    putInt(p, "windowsEventId", rs, "windows_event_id");
                    putText(p, "providerName", rs.getString("provider_name"));
                    long windowsRecord = rs.getLong("record_id");
                    if (rs.wasNull()) {
                        p.putNull("windowsRecordId");
                    } else {
                        p.put("windowsRecordId", windowsRecord);
                    }
                    putText(p, "level", rs.getString("level"));
                    putText(p, "category", rs.getString("category"));
                    p.put("occurredAt", Instant.ofEpochMilli(occurredMs).toString());
                    p.put("occurredAtEpochMs", occurredMs);
                    p.put("storedAt", Instant.ofEpochMilli(storedMs).toString());
                    p.put("storedAtEpochMs", storedMs);
                    putText(p, "message", rs.getString("message"));
                    out.put(entry.id(), new SyncRecord(entry.id(), recordId(computerId, RecordType.HARDWARE_EVENT, id),
                            RecordType.HARDWARE_EVENT.name(), p));
                }
            }
        }
    }

    /** Stable idempotency key; identical on every retry and across restarts. */
    public static String recordId(String computerId, RecordType type, long sourceId) {
        return computerId + "/" + type.name() + "/" + sourceId;
    }

    // ---- outcomes ------------------------------------------------------------------

    /** Marks records SYNCED. Call only for ids the destination explicitly acknowledged. */
    public void markSynced(Collection<Long> outboxIds) throws SQLException {
        updateStatus(outboxIds, "UPDATE sync_outbox SET status = 'SYNCED', synced_at = ?, last_attempt_at = ?, "
                + "attempts = attempts + 1, last_error = NULL WHERE status = 'PENDING' AND id IN (", clock.millis(), clock.millis());
    }

    /** A delivery attempt did not complete for these records; they stay PENDING. */
    public void recordAttemptFailure(Collection<Long> outboxIds, String error) throws SQLException {
        updateStatus(outboxIds, "UPDATE sync_outbox SET attempts = attempts + 1, last_attempt_at = ?, last_error = ? "
                + "WHERE status = 'PENDING' AND id IN (", clock.millis(), truncate(error));
    }

    /** Permanently refused (or unrepresentable) record: kept for inspection, never resent automatically. */
    public void markFailed(long outboxId, String reason) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE sync_outbox SET status = 'FAILED', attempts = attempts + 1, last_attempt_at = ?, last_error = ? "
                             + "WHERE status = 'PENDING' AND id = ?")) {
            ps.setLong(1, clock.millis());
            ps.setString(2, truncate(reason));
            ps.setLong(3, outboxId);
            ps.executeUpdate();
        }
    }

    private void updateStatus(Collection<Long> ids, String sqlPrefix, Object a, Object b) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        List<Long> list = new ArrayList<>(ids);
        try (Connection c = dataSource.getConnection()) {
            inImmediateTransaction(c, () -> {
                for (int from = 0; from < list.size(); from += 500) {
                    List<Long> part = list.subList(from, Math.min(list.size(), from + 500));
                    try (PreparedStatement ps = c.prepareStatement(sqlPrefix + placeholders(part.size()) + ")")) {
                        ps.setObject(1, a);
                        ps.setObject(2, b);
                        for (int i = 0; i < part.size(); i++) {
                            ps.setLong(3 + i, part.get(i));
                        }
                        ps.executeUpdate();
                    }
                }
                return 0;
            });
        }
    }

    // ---- counts ----------------------------------------------------------------------

    /**
     * Exact counts. {@code pending} includes rows stored locally but not yet
     * copied into the outbox, so the number is correct even before the first
     * sync cycle (and while no destination is configured).
     */
    public QueueCounts counts() throws SQLException {
        Map<String, long[]> byType = new LinkedHashMap<>();
        for (RecordType t : RecordType.values()) {
            byType.put(t.name(), new long[3]);
        }
        try (Connection c = dataSource.getConnection()) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT record_type, status, COUNT(*) FROM sync_outbox GROUP BY record_type, status")) {
                while (rs.next()) {
                    long[] arr = byType.get(rs.getString(1));
                    if (arr == null) {
                        continue;
                    }
                    String status = rs.getString(2);
                    long n = rs.getLong(3);
                    if (PENDING.equals(status)) {
                        arr[0] += n;
                    } else if (SYNCED.equals(status)) {
                        arr[1] += n;
                    } else if (FAILED.equals(status)) {
                        arr[2] += n;
                    }
                }
            }
            for (RecordType t : RecordType.values()) {
                if (tableExists(c, t.table())) {
                    long cursor = parseLong(getState(c, cursorKey(t)), 0);
                    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + t.table() + " WHERE id > ?")) {
                        ps.setLong(1, cursor);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                byType.get(t.name())[0] += rs.getLong(1);
                            }
                        }
                    }
                }
            }
        }
        long p = 0, s = 0, f = 0;
        Map<String, TypeCounts> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : byType.entrySet()) {
            long[] a = e.getValue();
            out.put(e.getKey(), new TypeCounts(a[0], a[1], a[2]));
            p += a[0];
            s += a[1];
            f += a[2];
        }
        return new QueueCounts(p, s, f, out);
    }

    // ---- key/value state ------------------------------------------------------------

    public String getState(String key) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return getState(c, key);
        }
    }

    public Map<String, String> allState() throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT state_key, state_value FROM sync_state")) {
            while (rs.next()) {
                m.put(rs.getString(1), rs.getString(2));
            }
        }
        return m;
    }

    /** Writes several keys atomically; a null value deletes the key. */
    public void putState(Map<String, String> values) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            inImmediateTransaction(c, () -> {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    if (e.getValue() == null) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM sync_state WHERE state_key = ?")) {
                            ps.setString(1, e.getKey());
                            ps.executeUpdate();
                        }
                    } else {
                        putState(c, e.getKey(), e.getValue());
                    }
                }
                return 0;
            });
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    static String cursorKey(RecordType type) {
        return "cursor." + type.name();
    }

    private String getState(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT state_value FROM sync_state WHERE state_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void putState(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sync_state (state_key, state_value, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(state_key) DO UPDATE SET state_value = excluded.state_value, updated_at = excluded.updated_at")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setLong(3, clock.millis());
            ps.executeUpdate();
        }
    }

    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    /** BEGIN IMMEDIATE avoids SQLite's read-then-write lock-upgrade failure when the collector writes concurrently. */
    private <T> T inImmediateTransaction(Connection c, SqlWork<T> work) throws SQLException {
        boolean previousAuto = c.getAutoCommit();
        if (!previousAuto) {
            c.setAutoCommit(true);
        }
        try (Statement st = c.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            try {
                T result = work.run();
                st.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    st.execute("ROLLBACK");
                } catch (SQLException ignored) {
                    // the original failure is the one worth reporting
                }
                throw e;
            }
        } finally {
            if (!previousAuto) {
                c.setAutoCommit(false);
            }
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long scalarLong(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null ? fallback : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<Long, OutboxEntry> indexBySource(List<OutboxEntry> group) {
        Map<Long, OutboxEntry> m = new LinkedHashMap<>();
        for (OutboxEntry e : group) {
            m.put(e.sourceId(), e);
        }
        return m;
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ",?");
        }
        return sb.toString();
    }

    private static void bind(PreparedStatement ps, List<OutboxEntry> group) throws SQLException {
        for (int i = 0; i < group.size(); i++) {
            ps.setLong(i + 1, group.get(i).sourceId());
        }
    }

    private static void putText(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void putInt(ObjectNode node, String name, ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        if (rs.wasNull()) {
            node.putNull(name);
        } else {
            node.put(name, v);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 400 ? s : s.substring(0, 400) + "...";
    }
}
