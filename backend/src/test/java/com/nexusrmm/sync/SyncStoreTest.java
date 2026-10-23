package com.nexusrmm.sync;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static com.nexusrmm.sync.SyncTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncStoreTest {

    private DataSource ds;
    private SyncStore store;

    @BeforeEach
    void setUp() throws Exception {
        Path db = newDbPath();
        ds = dataSource(db);
        createAll(ds);
        store = new SyncStore(ds, Clock.systemUTC());
    }

    @Test
    void existingHistoryIsQueuedExactlyOnceEvenIfEnqueueRunsRepeatedly() throws Exception {
        insertSamples(ds, 7, 1_700_000_000_000L);
        insertEvent(ds, 1_700_000_100_000L);
        store.initializeCursors(true);

        assertEquals(8, store.enqueueNew(100));
        assertEquals(0, store.enqueueNew(100), "second run must not duplicate");
        insertSample(ds, "RAM_UTILIZATION", 50.0, 1_700_000_200_000L);
        assertEquals(1, store.enqueueNew(100), "only the new row is queued");
        assertEquals(9, scalar(ds, "SELECT COUNT(*) FROM sync_outbox"));
    }

    @Test
    void enqueueIsBoundedPerChunk() throws Exception {
        insertSamples(ds, 10, 1_700_000_000_000L);
        store.initializeCursors(true);
        assertEquals(4, store.enqueueNew(4));
        assertEquals(4, store.enqueueNew(4));
        assertEquals(2, store.enqueueNew(4));
        assertEquals(0, store.enqueueNew(4));
    }

    @Test
    void backfillDisabledOnlyQueuesRowsStoredAfterFirstInitialization() throws Exception {
        insertSamples(ds, 5, 1_700_000_000_000L);
        store.initializeCursors(false);
        assertEquals(0, store.enqueueNew(100), "pre-existing history is not eligible when backfill is off");
        insertSample(ds, "CPU_UTILIZATION", 1.0, 1_700_000_900_000L);
        assertEquals(1, store.enqueueNew(100));
        store.initializeCursors(true); // never moves an existing watermark
        assertEquals(0, store.enqueueNew(100));
    }

    @Test
    void realZeroAndUnavailableAreDistinctInThePayloadAndOriginalTimestampIsPreserved() throws Exception {
        long ts = 1_700_000_123_456L;
        insertSample(ds, "pc-1", "CPU_UTILIZATION", null, 0.0, "AVAILABLE", ts, null);
        insertSample(ds, "pc-1", "CPU_TEMPERATURE", null, null, "UNAVAILABLE", ts, "no sensor");
        store.initializeCursors(true);
        store.enqueueNew(100);

        SyncStore.LoadResult loaded = store.loadRecords(store.nextPending(0, 10));
        assertEquals(2, loaded.records().size());
        JsonNode zero = loaded.records().get(0).payload();
        JsonNode missing = loaded.records().get(1).payload();

        assertTrue(zero.get("value").isNumber(), "a real zero stays a number");
        assertEquals(0.0, zero.get("value").asDouble());
        assertEquals("AVAILABLE", zero.get("availabilityStatus").asText());

        assertTrue(missing.has("value") && missing.get("value").isNull(), "unavailable is an explicit JSON null");
        assertEquals("UNAVAILABLE", missing.get("availabilityStatus").asText());
        assertEquals("no sensor", missing.get("diagnostic").asText());

        assertEquals(ts, zero.get("timestampEpochMs").asLong());
        assertEquals(Instant.ofEpochMilli(ts).toString(), zero.get("timestamp").asText());
    }

    @Test
    void recordIdsAreStableAcrossLoads() throws Exception {
        long id = insertSample(ds, "CPU_UTILIZATION", 5.0, 1_700_000_000_000L);
        store.initializeCursors(true);
        store.enqueueNew(10);
        String a = store.loadRecords(store.nextPending(0, 10)).records().get(0).recordId();
        String b = new SyncStore(ds, Clock.systemUTC()).loadRecords(store.nextPending(0, 10)).records().get(0).recordId();
        assertEquals(a, b);
        assertEquals("pc-1/METRIC_SAMPLE/" + id, a);
    }

    @Test
    void nonCanonicalTimestampsAreNeverSentAsIfTheyWereValid() throws Exception {
        long id = insertSample(ds, "CPU_UTILIZATION", 5.0, 1_700_000_000_000L);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE metric_sample SET timestamp = '2026-01-01 10:00:00' WHERE id = " + id);
        }
        store.initializeCursors(true);
        store.enqueueNew(10);
        SyncStore.LoadResult r = store.loadRecords(store.nextPending(0, 10));
        assertTrue(r.records().isEmpty());
        assertEquals(1, r.unsendable().size());
    }

    @Test
    void queueSurvivesRestartAndCountsAreExact() throws Exception {
        insertSamples(ds, 6, 1_700_000_000_000L);
        insertEvent(ds, 1_700_000_050_000L);
        store.initializeCursors(true);

        // Before anything is queued, locally stored rows already count as pending.
        assertEquals(7, store.counts().pending());

        store.enqueueNew(100);
        List<SyncStore.OutboxEntry> first = store.nextPending(0, 3);
        store.markSynced(first.stream().map(SyncStore.OutboxEntry::id).toList());
        store.markFailed(store.nextPending(0, 10).get(0).id(), "refused");

        // "Restart": brand-new store object over the same file.
        SyncStore reopened = new SyncStore(ds, Clock.systemUTC());
        SyncStore.QueueCounts c = reopened.counts();
        assertEquals(3, c.synced());
        assertEquals(1, c.failed());
        assertEquals(3, c.pending());
        assertEquals(7, c.total());
        assertEquals(3, c.byType().get("METRIC_SAMPLE").synced());
        assertEquals(1, c.byType().get("HARDWARE_EVENT").pending() + c.byType().get("HARDWARE_EVENT").synced()
                + c.byType().get("HARDWARE_EVENT").failed());
    }

    @Test
    void failedAttemptKeepsRecordsPendingAndCountsAttempts() throws Exception {
        insertSamples(ds, 2, 1_700_000_000_000L);
        store.initializeCursors(true);
        store.enqueueNew(10);
        List<Long> ids = store.nextPending(0, 10).stream().map(SyncStore.OutboxEntry::id).toList();
        store.recordAttemptFailure(ids, "boom");
        store.recordAttemptFailure(ids, "boom again");
        assertEquals(2, store.nextPending(0, 10).size());
        assertEquals(2, store.nextPending(0, 10).get(0).attempts());
        assertEquals(2, store.counts().pending());
        assertEquals(0, store.counts().synced());
    }
}
