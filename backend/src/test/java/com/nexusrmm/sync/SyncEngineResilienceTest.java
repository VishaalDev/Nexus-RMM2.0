package com.nexusrmm.sync;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.nexusrmm.sync.SyncTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end resilience scenarios: real SQLite file, real JDK HTTP client, a
 * controlled local HTTP server standing in for a destination. No external network.
 */
class SyncEngineResilienceTest {

    private DataSource ds;
    private TestClock clock;
    private static final long TS = 1_700_000_000_000L;

    @BeforeEach
    void setUp() throws Exception {
        ds = dataSource(newDbPath());
        createAll(ds);
        clock = new TestClock(Instant.parse("2026-10-22T10:00:00Z"));
        new SyncStore(ds, clock).initializeCursors(true);
    }

    private SyncEngine engineFor(String url, int batch) {
        return engine(ds, settings(url, batch), clock);
    }

    // ---- successful synchronization -------------------------------------------------

    @Test
    void successfulSyncMarksOnlyAcknowledgedRecordsAndPreservesOriginalValues() throws Exception {
        insertSample(ds, "pc-1", "CPU_UTILIZATION", null, 0.0, "AVAILABLE", TS, null);
        insertSample(ds, "pc-1", "CPU_TEMPERATURE", null, null, "UNAVAILABLE", TS + 1000, "no sensor");
        insertEvent(ds, TS + 2000);
        try (FakeDestination d = new FakeDestination()) {
            SyncEngine e = engineFor(d.url(), 100);
            SyncCycleResult r = e.runCycle(true);

            assertEquals(SyncCycleResult.Outcome.SUCCESS, r.outcome());
            assertEquals(3, r.acknowledged());
            assertEquals(3, d.stored.size());

            SyncStatus s = e.status();
            assertEquals(0, s.queue().pending());
            assertEquals(3, s.queue().synced());
            assertEquals("UP_TO_DATE", s.state());
            assertEquals("REACHABLE", s.connectivity());
            assertNotNull(s.lastSuccessfulSyncAt());
            assertNull(s.lastError());

            JsonNode records = d.requestBodies.get(0).get("records");
            JsonNode zero = records.get(0).get("payload");
            JsonNode missing = records.get(1).get("payload");
            assertEquals(0.0, zero.get("value").asDouble());
            assertTrue(missing.get("value").isNull());
            assertEquals(TS, zero.get("timestampEpochMs").asLong());
        }
    }

    // ---- outage / failure preserves data -------------------------------------------------

    @Test
    void connectionFailurePreservesEveryRecordAndReportsUnreachable() throws Exception {
        insertSamples(ds, 5, TS);
        String url = "http://127.0.0.1:" + freePortNotListening() + "/ingest";
        SyncEngine e = engineFor(url, 100);

        SyncCycleResult r = e.runCycle(true);
        assertEquals(SyncCycleResult.Outcome.FAILED, r.outcome());
        assertEquals(SyncErrorCategory.CONNECTION_FAILED, r.errorCategory());

        SyncStatus s = e.status();
        assertEquals(5, s.queue().pending());
        assertEquals(0, s.queue().synced());
        assertEquals(0, s.queue().failed());
        assertEquals("RETRYING", s.state());
        assertEquals("UNREACHABLE", s.connectivity());
        assertEquals(1, s.consecutiveFailures());
        assertEquals("CONNECTION_FAILED", s.lastError().category());
        assertNotNull(s.lastError().nextStep());
        assertNull(s.lastSuccessfulSyncAt(), "no success is ever invented");
        assertNotNull(s.lastAttemptAt());
        assertNotNull(s.nextAttemptAt());
    }

    @Test
    void httpFailureIsReachableButErroringAndKeepsDataPending() throws Exception {
        insertSamples(ds, 3, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 503, "{}");
            SyncEngine e = engineFor(d.url(), 100);
            e.runCycle(true);
            SyncStatus s = e.status();
            assertEquals(3, s.queue().pending());
            assertEquals("REACHABLE", s.connectivity(), "the destination did answer");
            assertEquals("SERVER_ERROR", s.lastError().category());
            assertTrue(d.stored.isEmpty());
        }
    }

    @Test
    void localCollectionContinuesAndBacklogDrainsOnceTheDestinationReturns() throws Exception {
        insertSamples(ds, 3, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 500, "{}");
            SyncEngine e = engineFor(d.url(), 100);
            e.runCycle(true);

            // The collector keeps writing during the outage; nothing about sync blocks it.
            insertSamples(ds, 4, TS + 60_000);
            e.runCycle(true);
            assertEquals(7, e.status().queue().pending());
            assertEquals(0, e.status().queue().synced());

            d.behavior = FakeDestination::ackAll;
            SyncCycleResult r = e.runCycle(true);
            assertEquals(SyncCycleResult.Outcome.SUCCESS, r.outcome());
            assertEquals(7, d.stored.size());
            assertEquals(0, e.status().queue().pending());
            assertEquals(7, e.status().queue().synced());
            assertEquals(0, e.status().consecutiveFailures());
            assertNull(e.status().lastError(), "a successful sync clears the current error");
        }
    }

    // ---- idempotent retries -------------------------------------------------------------------

    @Test
    void lostAcknowledgementIsRetriedWithIdenticalIdsAndDestinationEndsWithNoDuplicates() throws Exception {
        insertSamples(ds, 4, TS);
        try (FakeDestination d = new FakeDestination()) {
            // Destination stores the batch but the response is a 500 (ack lost).
            d.behavior = (ex, req, dest) -> {
                req.get("records").forEach(r -> dest.stored.add(r.get("recordId").asText()));
                dest.respond(ex, 500, "{}");
            };
            SyncEngine e = engineFor(d.url(), 100);
            e.runCycle(true);
            assertEquals(4, e.status().queue().pending(), "without an ack nothing is synchronized");
            assertEquals(0, e.status().queue().synced());

            d.behavior = FakeDestination::ackAll;
            e.runCycle(true);

            assertEquals(d.receivedIdsPerRequest.get(0), d.receivedIdsPerRequest.get(1), "identical record ids on retry");
            assertEquals(4, d.stored.size(), "destination deduplicates by recordId");
            assertEquals(4, e.status().queue().synced());
        }
    }

    // ---- bounded batches ------------------------------------------------------------------------

    @Test
    void batchesAreBoundedAndEverythingIsDelivered() throws Exception {
        insertSamples(ds, 10, TS);
        try (FakeDestination d = new FakeDestination()) {
            SyncEngine e = engineFor(d.url(), 3);
            e.runCycle(true);
            assertEquals(4, d.requests.get());
            assertTrue(d.receivedIdsPerRequest.stream().allMatch(ids -> ids.size() <= 3));
            assertEquals(10, d.stored.size());
            assertEquals(10, e.status().queue().synced());
        }
    }

    // ---- partial batch acknowledgement ------------------------------------------------------------

    @Test
    void partialAcknowledgementResendsOnlyTheUnacknowledgedRemainder() throws Exception {
        insertSamples(ds, 6, TS);
        try (FakeDestination d = new FakeDestination()) {
            // Acknowledge only the first 4 of the 6 and say nothing about the rest.
            d.behavior = (ex, req, dest) -> {
                List<String> ids = new ArrayList<>();
                req.get("records").forEach(r -> ids.add(r.get("recordId").asText()));
                List<String> acked = ids.size() > 2 ? ids.subList(0, 4) : ids;
                dest.stored.addAll(acked);
                dest.respond(ex, 200, FakeDestination.ackJson(req, acked, null));
            };
            SyncEngine e = engineFor(d.url(), 100);
            SyncCycleResult first = e.runCycle(true);

            assertEquals(SyncCycleResult.Outcome.PARTIAL, first.outcome());
            assertEquals(4, first.acknowledged());
            assertEquals(2, first.retained());
            SyncStatus mid = e.status();
            assertEquals(4, mid.queue().synced());
            assertEquals(2, mid.queue().pending());
            assertEquals("RETRYING", mid.state());
            assertNotNull(mid.lastSuccessfulSyncAt(), "progress was acknowledged");

            d.behavior = FakeDestination::ackAll;
            e.runCycle(true);

            List<String> second = d.receivedIdsPerRequest.get(1);
            assertEquals(2, second.size(), "acknowledged records are never resent");
            assertTrue(Set.copyOf(second).stream().noneMatch(id -> d.receivedIdsPerRequest.get(0).subList(0, 4).contains(id)));
            assertEquals(6, d.stored.size());
            assertEquals(6, e.status().queue().synced());
        }
    }

    @Test
    void retryableRejectionStaysPendingWhilePermanentRejectionBecomesFailedAndIsNotResent() throws Exception {
        insertSamples(ds, 3, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> {
                List<String> ids = new ArrayList<>();
                req.get("records").forEach(r -> ids.add(r.get("recordId").asText()));
                String rejected = "[{\"recordId\":\"" + ids.get(1) + "\",\"reason\":\"try later\",\"retryable\":true},"
                        + "{\"recordId\":\"" + ids.get(2) + "\",\"reason\":\"schema violation\"}]";
                dest.stored.add(ids.get(0));
                dest.respond(ex, 200, FakeDestination.ackJson(req, List.of(ids.get(0)), rejected));
            };
            SyncEngine e = engineFor(d.url(), 100);
            e.runCycle(true);
            SyncStatus s = e.status();
            assertEquals(1, s.queue().synced());
            assertEquals(1, s.queue().pending());
            assertEquals(1, s.queue().failed());

            e.runCycle(true);
            assertEquals(1, d.receivedIdsPerRequest.get(1).size(), "only the retryable record is resent");
            assertEquals(1, e.status().queue().failed(), "permanent rejection is not resent");
        }
    }

    @Test
    void successResponseThatAcknowledgesNothingIsAFailureNotASuccess() throws Exception {
        insertSamples(ds, 3, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 200, FakeDestination.ackJson(req, List.of(), null));
            SyncEngine e = engineFor(d.url(), 100);
            SyncCycleResult r = e.runCycle(true);
            assertEquals(SyncCycleResult.Outcome.FAILED, r.outcome());
            assertEquals(SyncErrorCategory.NOT_ACKNOWLEDGED, r.errorCategory());
            assertEquals(3, e.status().queue().pending());
            assertEquals(0, e.status().queue().synced());
            assertNull(e.status().lastSuccessfulSyncAt());
        }
    }

    @Test
    void acknowledgementsForUnknownIdsAreIgnored() throws Exception {
        insertSamples(ds, 2, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> {
                List<String> ids = new ArrayList<>();
                req.get("records").forEach(r -> ids.add(r.get("recordId").asText()));
                ids.add("some-other-device/METRIC_SAMPLE/999");
                dest.respond(ex, 200, FakeDestination.ackJson(req, ids, null));
            };
            SyncEngine e = engineFor(d.url(), 100);
            e.runCycle(true);
            assertEquals(2, e.status().queue().synced());
            assertEquals(2, e.status().queue().total());
        }
    }

    // ---- restart ----------------------------------------------------------------------------------

    @Test
    void pendingWorkAndRetryStateSurviveARestart() throws Exception {
        insertSamples(ds, 5, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 500, "{}");
            SyncEngine before = engineFor(d.url(), 100);
            before.runCycle(true);
            assertEquals(1, before.status().consecutiveFailures());

            // New engine, new store objects, same database file == application restart.
            SyncEngine after = engineFor(d.url(), 100);
            SyncStatus s = after.status();
            assertEquals(5, s.queue().pending());
            assertEquals(1, s.consecutiveFailures());
            assertEquals("SERVER_ERROR", s.lastError().category());
            assertNotNull(s.nextAttemptAt());

            d.behavior = FakeDestination::ackAll;
            assertEquals(SyncCycleResult.Outcome.SUCCESS, after.runCycle(true).outcome());
            assertEquals(5, d.stored.size());
        }
    }

    @Test
    void restartAfterAckWasSentButBeforeItWasRecordedIsSafeBecauseRetriesAreIdempotent() throws Exception {
        insertSamples(ds, 3, TS);
        try (FakeDestination d = new FakeDestination()) {
            // Simulate a crash: destination stored+acked, but local state never updated.
            SyncEngine e = engineFor(d.url(), 100);
            SyncStore store = new SyncStore(ds, clock);
            store.enqueueNew(100);
            var loaded = store.loadRecords(store.nextPending(0, 100));
            loaded.records().forEach(r -> d.stored.add(r.recordId()));

            e.runCycle(true);
            assertEquals(3, d.stored.size(), "no duplicates on the destination");
            assertEquals(3, e.status().queue().synced());
        }
    }

    // ---- backoff ------------------------------------------------------------------------------------

    @Test
    void automaticAttemptsWaitForBackoffButManualSyncDoesNot() throws Exception {
        insertSamples(ds, 2, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 500, "{}");
            SyncEngine e = engineFor(d.url(), 100);
            e.runCycle(false);
            assertEquals(1, d.requests.get());

            assertEquals(SyncCycleResult.Outcome.BACKING_OFF, e.runCycle(false).outcome());
            assertEquals(1, d.requests.get(), "no request while backing off");

            e.runCycle(true);
            assertEquals(2, d.requests.get(), "manual sync ignores the backoff window");

            clock.advance(Duration.ofMinutes(10));
            d.behavior = FakeDestination::ackAll;
            assertEquals(SyncCycleResult.Outcome.SUCCESS, e.runCycle(false).outcome());
        }
    }

    @Test
    void backoffGrowsExponentiallyAndIsCapped() {
        SyncSettings s = settings("http://127.0.0.1:1/ingest", 10); // initial 5s, max 60s, x2
        SyncEngine e = engine(ds, s, clock);
        assertEquals(Duration.ofSeconds(5), e.backoffDelay(1, null));
        assertEquals(Duration.ofSeconds(10), e.backoffDelay(2, null));
        assertEquals(Duration.ofSeconds(20), e.backoffDelay(3, null));
        assertEquals(Duration.ofSeconds(40), e.backoffDelay(4, null));
        assertEquals(Duration.ofSeconds(60), e.backoffDelay(5, null));
        assertEquals(Duration.ofSeconds(60), e.backoffDelay(500, null), "bounded, no overflow");
        assertEquals(Duration.ofSeconds(30), e.backoffDelay(1, Duration.ofSeconds(30)), "Retry-After can lengthen");
        assertEquals(Duration.ofSeconds(60), e.backoffDelay(1, Duration.ofHours(5)), "Retry-After is capped too");
    }

    @Test
    void consecutiveFailuresAccumulateAcrossCycles() throws Exception {
        insertSamples(ds, 1, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 500, "{}");
            SyncEngine e = engineFor(d.url(), 100);
            e.runCycle(true);
            e.runCycle(true);
            e.runCycle(true);
            assertEquals(3, e.status().consecutiveFailures());
            assertEquals(Instant.parse("2026-10-22T10:00:20Z"), e.status().nextAttemptAt(), "5s * 2^2 from the (frozen) test clock");
        }
    }

    // ---- configuration ----------------------------------------------------------------------------------

    @Test
    void missingConfigurationReportsNotConfiguredAndNeverContactsAnything() throws Exception {
        insertSamples(ds, 4, TS);
        SyncSettings none = settings(null, 10);
        SyncEngine e = engine(ds, none, clock);

        SyncCycleResult r = e.runCycle(true);
        assertEquals(SyncCycleResult.Outcome.NOT_CONFIGURED, r.outcome());

        SyncStatus s = e.status();
        assertEquals("NOT_CONFIGURED", s.state());
        assertEquals("NOT_CONFIGURED", s.connectivity());
        assertFalse(s.configuration().configured());
        assertFalse(s.configuration().destinationSet());
        assertEquals(4, s.queue().pending(), "locally stored data is still counted as unsynchronized");
        assertEquals(0, s.queue().synced());
        assertNull(s.lastAttemptAt());
        assertNull(s.lastSuccessfulSyncAt());
        assertNotNull(s.nextStep());
        assertTrue(s.nextStep().contains("nexus.sync.destination-url"));
        assertEquals(0, scalar(ds, "SELECT COUNT(*) FROM sync_outbox"), "nothing is queued or marked without a destination");
    }

    @Test
    void invalidConfigurationIsDistinctFromMissingConfiguration() throws Exception {
        SyncEngine e = engine(ds, settings("ftp://nope/x", 10), clock);
        assertEquals(SyncCycleResult.Outcome.NOT_CONFIGURED, e.runCycle(true).outcome());
        SyncStatus s = e.status();
        assertEquals("CONFIG_INVALID", s.state());
        assertTrue(s.configuration().destinationSet());
        assertNotNull(s.configuration().issue());
    }

    @Test
    void statusNeverContainsTheAuthToken() throws Exception {
        insertSamples(ds, 1, TS);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 401, "token secret-token-123 invalid");
            SyncEngine e = engineFor(d.url(), 10);
            e.runCycle(true);
            // Plain mapper + an Instant serializer: avoids depending on the jsr310 module in this unit test.
            var module = new com.fasterxml.jackson.databind.module.SimpleModule();
            module.addSerializer(Instant.class, new com.fasterxml.jackson.databind.ser.std.ToStringSerializer(Instant.class));
            String json = new com.fasterxml.jackson.databind.ObjectMapper().registerModule(module).writeValueAsString(e.status());
            assertFalse(json.contains("secret-token-123"));
            assertTrue(e.status().configuration().authConfigured());
            assertEquals("AUTH_FAILED", e.status().lastError().category());
        }
    }

    // ---- nothing to do / counts --------------------------------------------------------------------------

    @Test
    void emptyQueueMakesNoRequestAndDoesNotInventAnAttempt() throws Exception {
        try (FakeDestination d = new FakeDestination()) {
            SyncEngine e = engineFor(d.url(), 10);
            SyncCycleResult r = e.runCycle(true);
            assertEquals(SyncCycleResult.Outcome.NOTHING_TO_SYNC, r.outcome());
            assertEquals(0, d.requests.get());
            SyncStatus s = e.status();
            assertEquals("IDLE", s.state());
            assertEquals("UNKNOWN", s.connectivity());
            assertNull(s.lastAttemptAt());
            assertNull(s.lastSuccessfulSyncAt());
        }
    }

    @Test
    void newDataAfterASyncBecomesPendingAgain() throws Exception {
        insertSamples(ds, 2, TS);
        try (FakeDestination d = new FakeDestination()) {
            SyncEngine e = engineFor(d.url(), 10);
            e.runCycle(true);
            insertSamples(ds, 3, TS + 100_000);
            SyncStatus s = e.status();
            assertEquals(3, s.queue().pending());
            assertEquals(2, s.queue().synced());
            assertEquals("PENDING", s.state());
            e.runCycle(true);
            assertEquals(5, d.stored.size());
        }
    }

    // ---- single flight ------------------------------------------------------------------------------------

    @Test
    void overlappingCyclesAreRejectedNotRunInParallel() throws Exception {
        insertSamples(ds, 2, TS);
        CountDownLatch inRequest = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> {
                inRequest.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                FakeDestination.ackAll(ex, req, dest);
            };
            SyncEngine e = engineFor(d.url(), 10);
            Thread t = new Thread(() -> e.runCycle(true));
            t.start();
            assertTrue(inRequest.await(5, TimeUnit.SECONDS));

            assertTrue(e.isRunning());
            assertEquals("SYNCING", e.status().state());
            assertEquals(SyncCycleResult.Outcome.ALREADY_RUNNING, e.runCycle(true).outcome());

            release.countDown();
            t.join(5000);
            assertFalse(e.isRunning());
            assertEquals(1, d.requests.get());
        }
    }

    // ---- concurrent collection ---------------------------------------------------------------------

    @Test
    void collectionWritesRunningConcurrentlyWithSyncCyclesNeitherFailNorLoseData() throws Exception {
        try (FakeDestination d = new FakeDestination()) {
            SyncEngine e = engineFor(d.url(), 25);
            java.util.concurrent.atomic.AtomicReference<Throwable> writerFailure = new java.util.concurrent.atomic.AtomicReference<>();
            int total = 300;
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < total; i++) {
                        insertSample(ds, "CPU_UTILIZATION", (double) (i % 100), TS + i * 1000L);
                    }
                } catch (Throwable t) {
                    writerFailure.set(t);
                }
            });
            writer.start();
            while (writer.isAlive()) {
                e.runCycle(true);
            }
            writer.join();
            assertNull(writerFailure.get(), "the collector's inserts must never fail because of sync: " + writerFailure.get());

            e.runCycle(true); // final drain
            assertEquals(total, scalar(ds, "SELECT COUNT(*) FROM metric_sample"));
            assertEquals(total, d.stored.size());
            assertEquals(total, e.status().queue().synced());
            assertEquals(0, e.status().queue().pending());
        }
    }
}
