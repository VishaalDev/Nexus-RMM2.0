package com.nexusrmm.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * The synchronization state machine. Plain Java (no Spring), so it is
 * testable against a real SQLite file and a real local HTTP server.
 *
 * <h3>Guarantees</h3>
 * <ul>
 *   <li>A record becomes SYNCED only after the destination lists its
 *       {@code recordId} in a well-formed acknowledgement. Nothing else
 *       ever sets that state.</li>
 *   <li>Every failure (connection, timeout, HTTP error, bad response,
 *       missing acknowledgement) leaves the records PENDING.</li>
 *   <li>Partial acknowledgements are honoured record by record; only the
 *       unacknowledged remainder is sent again.</li>
 *   <li>Record ids are stable, so a retry after a lost response is
 *       idempotent on the destination.</li>
 *   <li>Retry timing is bounded exponential backoff and is persisted, so it
 *       survives restarts.</li>
 *   <li>Telemetry collection never calls into this class.</li>
 * </ul>
 */
public final class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    // sync_state keys
    static final String K_LAST_ATTEMPT_AT = "lastAttemptAt";
    static final String K_LAST_ATTEMPT_RESULT = "lastAttemptResult";
    static final String K_REACHABLE = "lastAttemptReachable";
    static final String K_LAST_SUCCESS_AT = "lastSuccessAt";
    static final String K_FAILURES = "consecutiveFailures";
    static final String K_NEXT_ATTEMPT_AT = "nextAttemptAt";
    static final String K_ERR_CATEGORY = "lastErrorCategory";
    static final String K_ERR_MESSAGE = "lastErrorMessage";
    static final String K_ERR_AT = "lastErrorAt";

    private final SyncStore store;
    private final SyncClient client;
    private final SyncSettings settings;
    private final Supplier<SyncDevice> device;
    private final Clock clock;
    private final int pollIntervalSeconds;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean running;

    public SyncEngine(SyncStore store, SyncClient client, SyncSettings settings, Supplier<SyncDevice> device,
                      Clock clock, int pollIntervalSeconds) {
        this.store = store;
        this.client = client;
        this.settings = settings;
        this.device = device;
        this.clock = clock;
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Runs one synchronization pass: queue newly stored rows, then send
     * bounded batches until the queue is drained, the per-cycle batch limit
     * is reached, or a failure occurs.
     *
     * @param manual true for "Sync Now": ignores the retry backoff window
     */
    public SyncCycleResult runCycle(boolean manual) {
        if (!settings.configured()) {
            return SyncCycleResult.of(SyncCycleResult.Outcome.NOT_CONFIGURED,
                    settings.destinationMissing() ? "No synchronization destination is configured"
                            : "Synchronization configuration is invalid: " + settings.issue());
        }
        if (!lock.tryLock()) {
            return SyncCycleResult.of(SyncCycleResult.Outcome.ALREADY_RUNNING, "A synchronization is already running");
        }
        running = true;
        try {
            return doCycle(manual);
        } catch (SQLException e) {
            log.error("Synchronization could not access the local queue", e);
            return new SyncCycleResult(SyncCycleResult.Outcome.FAILED, 0, 0, 0, 0, SyncErrorCategory.STORAGE_ERROR,
                    "Local sync queue error: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected synchronization failure", e);
            return new SyncCycleResult(SyncCycleResult.Outcome.FAILED, 0, 0, 0, 0, SyncErrorCategory.INTERNAL_ERROR,
                    "Unexpected error: " + e.getClass().getSimpleName());
        } finally {
            running = false;
            lock.unlock();
        }
    }

    private SyncCycleResult doCycle(boolean manual) throws SQLException {
        Instant start = clock.instant();
        if (!manual) {
            Instant next = parseInstant(store.getState(K_NEXT_ATTEMPT_AT));
            if (next != null && start.isBefore(next)) {
                return SyncCycleResult.of(SyncCycleResult.Outcome.BACKING_OFF, "Waiting for retry backoff until " + next);
            }
        }

        // Queue everything stored locally so far (bounded chunks, short transactions).
        while (store.enqueueNew(settings.enqueueChunkSize()) >= settings.enqueueChunkSize()) {
            // keep draining while chunks come back full
        }

        int attempted = 0;
        int acknowledged = 0;
        int rejected = 0;
        int retained = 0;
        SyncTransportException failure = null;
        long afterId = 0;
        boolean contacted = false;

        for (int b = 0; b < settings.maxBatchesPerCycle(); b++) {
            List<SyncStore.OutboxEntry> entries = store.nextPending(afterId, settings.batchSize());
            if (entries.isEmpty()) {
                break;
            }
            afterId = entries.get(entries.size() - 1).id();

            SyncStore.LoadResult loaded = store.loadRecords(entries);
            for (Map.Entry<Long, String> u : loaded.unsendable().entrySet()) {
                store.markFailed(u.getKey(), u.getValue());
                rejected++;
            }
            if (loaded.records().isEmpty()) {
                continue;
            }

            SyncBatch batch = new SyncBatch(UUID.randomUUID().toString(), clock.instant(), device.get(), loaded.records());
            attempted += batch.records().size();
            contacted = true;

            SyncAck ack;
            try {
                ack = client.send(batch);
            } catch (SyncTransportException e) {
                failure = e;
                store.recordAttemptFailure(ids(batch.records()), e.getMessage());
                break;
            }

            BatchOutcome outcome = applyAck(batch, ack);
            acknowledged += outcome.acknowledged;
            rejected += outcome.rejected;
            retained += outcome.retained;

            if (outcome.acknowledged == 0 && outcome.rejected == 0) {
                failure = new SyncTransportException(SyncErrorCategory.NOT_ACKNOWLEDGED,
                        "The destination acknowledged none of the " + batch.records().size() + " record(s) in the batch");
                break;
            }
            // Persist after every batch so a crash leaves truthful state.
            persistProgress(outcome.acknowledged > 0);
        }

        if (!contacted && failure == null) {
            return new SyncCycleResult(SyncCycleResult.Outcome.NOTHING_TO_SYNC, 0, 0, rejected, 0, null,
                    "No pending records to synchronize");
        }

        Instant now = clock.instant();
        Map<String, String> state = new LinkedHashMap<>();
        state.put(K_LAST_ATTEMPT_AT, Long.toString(now.toEpochMilli()));
        if (acknowledged > 0) {
            state.put(K_LAST_SUCCESS_AT, Long.toString(now.toEpochMilli()));
        }

        SyncCycleResult.Outcome outcome;
        SyncErrorCategory category = null;
        String message;
        int failures;

        if (failure != null) {
            outcome = acknowledged > 0 ? SyncCycleResult.Outcome.PARTIAL : SyncCycleResult.Outcome.FAILED;
            category = failure.category();
            message = failure.getMessage();
            failures = acknowledged > 0 ? 1 : parseInt(store.getState(K_FAILURES), 0) + 1;
            if (failure.destinationAnswered() || acknowledged > 0) {
                state.put(K_REACHABLE, "true");
            } else {
                state.put(K_REACHABLE, "false");
            }
        } else if (retained > 0) {
            outcome = SyncCycleResult.Outcome.PARTIAL;
            category = SyncErrorCategory.NOT_ACKNOWLEDGED;
            message = retained + " record(s) were not acknowledged and remain pending";
            failures = 1;
            state.put(K_REACHABLE, "true");
        } else {
            outcome = SyncCycleResult.Outcome.SUCCESS;
            message = acknowledged + " record(s) acknowledged by the destination";
            failures = 0;
            state.put(K_REACHABLE, "true");
        }

        state.put(K_LAST_ATTEMPT_RESULT, outcome.name());
        state.put(K_FAILURES, Integer.toString(failures));
        if (failures > 0) {
            Duration delay = backoffDelay(failures, failure == null ? null : failure.retryAfter());
            state.put(K_NEXT_ATTEMPT_AT, Long.toString(now.plus(delay).toEpochMilli()));
            state.put(K_ERR_CATEGORY, category.name());
            state.put(K_ERR_MESSAGE, message);
            state.put(K_ERR_AT, Long.toString(now.toEpochMilli()));
            log.warn("Synchronization {}: {} (category {}); next automatic attempt in {}s",
                    outcome, message, category, delay.toSeconds());
        } else {
            state.put(K_NEXT_ATTEMPT_AT, null);
            state.put(K_ERR_CATEGORY, null);
            state.put(K_ERR_MESSAGE, null);
            state.put(K_ERR_AT, null);
            log.info("Synchronization succeeded: {} acknowledged", acknowledged);
        }
        store.putState(state);
        return new SyncCycleResult(outcome, attempted, acknowledged, rejected, retained, category, message);
    }

    private record BatchOutcome(int acknowledged, int rejected, int retained) {
    }

    /** Applies a destination acknowledgement record by record. */
    private BatchOutcome applyAck(SyncBatch batch, SyncAck ack) throws SQLException {
        Map<String, SyncRecord> sent = new HashMap<>();
        for (SyncRecord r : batch.records()) {
            sent.put(r.recordId(), r);
        }
        Map<String, SyncAck.Rejection> rejections = new HashMap<>();
        for (SyncAck.Rejection r : ack.rejected()) {
            rejections.put(r.recordId(), r);
        }
        Set<String> acked = ack.acknowledged();

        List<Long> toSync = new ArrayList<>();
        List<Long> toRetry = new ArrayList<>();
        Map<Long, String> retryReason = new HashMap<>();
        int rejectedPermanently = 0;

        for (SyncRecord record : batch.records()) {
            boolean isAcked = acked.contains(record.recordId());
            SyncAck.Rejection rejection = rejections.get(record.recordId());
            if (isAcked && rejection == null) {
                toSync.add(record.outboxId());
            } else if (rejection != null && !isAcked && !rejection.retryable()) {
                store.markFailed(record.outboxId(), "Rejected by destination: " + rejection.reason());
                rejectedPermanently++;
            } else {
                toRetry.add(record.outboxId());
                retryReason.put(record.outboxId(), rejection != null && !isAcked
                        ? "Destination asked for a retry: " + rejection.reason()
                        : isAcked ? "Contradictory acknowledgement (both acknowledged and rejected)"
                        : "Not acknowledged by destination");
            }
        }
        long unknown = acked.stream().filter(id -> !sent.containsKey(id)).count();
        if (unknown > 0) {
            log.warn("Destination acknowledged {} record id(s) that were not in the batch; ignored", unknown);
        }

        store.markSynced(toSync);
        if (!toRetry.isEmpty()) {
            Map<String, List<Long>> byReason = new LinkedHashMap<>();
            for (Long id : toRetry) {
                byReason.computeIfAbsent(retryReason.get(id), k -> new ArrayList<>()).add(id);
            }
            for (Map.Entry<String, List<Long>> e : byReason.entrySet()) {
                store.recordAttemptFailure(e.getValue(), e.getKey());
            }
        }
        return new BatchOutcome(toSync.size(), rejectedPermanently, toRetry.size());
    }

    private void persistProgress(boolean acknowledgedSome) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(K_LAST_ATTEMPT_AT, Long.toString(clock.millis()));
        m.put(K_REACHABLE, "true");
        if (acknowledgedSome) {
            m.put(K_LAST_SUCCESS_AT, Long.toString(clock.millis()));
        }
        store.putState(m);
    }

    /** initial * multiplier^(failures-1), capped at backoffMax; a Retry-After hint can lengthen it, also capped. */
    Duration backoffDelay(int failures, Duration retryAfter) {
        double factor = Math.pow(settings.backoffMultiplier(), Math.min(Math.max(failures - 1, 0), 30));
        double millis = settings.backoffInitial().toMillis() * factor;
        double capped = Math.min(millis, settings.backoffMax().toMillis());
        Duration delay = Duration.ofMillis((long) capped);
        if (retryAfter != null) {
            Duration bounded = retryAfter.compareTo(settings.backoffMax()) > 0 ? settings.backoffMax() : retryAfter;
            if (bounded.compareTo(delay) > 0) {
                delay = bounded;
            }
        }
        return delay;
    }

    // ---- status ---------------------------------------------------------------------------

    public SyncStatus status() throws SQLException {
        Map<String, String> st = store.allState();
        SyncStore.QueueCounts counts = store.counts();

        SyncStatus.Configuration cfg = new SyncStatus.Configuration(
                settings.configured(), !settings.destinationMissing(), settings.displayDestination(),
                settings.authConfigured(), settings.issue(), settings.warnings(), settings.batchSize(), pollIntervalSeconds);

        Instant lastAttempt = parseInstant(st.get(K_LAST_ATTEMPT_AT));
        Instant lastSuccess = parseInstant(st.get(K_LAST_SUCCESS_AT));
        Instant nextAttempt = parseInstant(st.get(K_NEXT_ATTEMPT_AT));
        int failures = parseInt(st.get(K_FAILURES), 0);
        String reachable = st.get(K_REACHABLE);

        SyncStatus.LastError lastError = null;
        if (st.get(K_ERR_CATEGORY) != null) {
            SyncErrorCategory cat = safeCategory(st.get(K_ERR_CATEGORY));
            lastError = new SyncStatus.LastError(cat.name(), st.get(K_ERR_MESSAGE),
                    parseInstant(st.get(K_ERR_AT)), cat.nextStep());
        }

        String state;
        String connectivity;
        String nextStep = null;
        boolean isRunning = running;

        if (settings.destinationMissing()) {
            state = "NOT_CONFIGURED";
            connectivity = "NOT_CONFIGURED";
            nextStep = "No synchronization destination is configured, so nothing is sent anywhere. Set nexus.sync.destination-url "
                    + "(environment variable NEXUS_SYNC_DESTINATION_URL) to the endpoint of a sync receiver, optionally set "
                    + "nexus.sync.auth-token (NEXUS_SYNC_AUTH_TOKEN), then restart the backend. Local monitoring is unaffected "
                    + "and all data stays queued locally.";
        } else if (!settings.configured()) {
            state = "CONFIG_INVALID";
            connectivity = "NOT_CONFIGURED";
            nextStep = "Fix the synchronization configuration and restart the backend: " + settings.issue();
        } else {
            connectivity = reachable == null ? "UNKNOWN" : ("true".equals(reachable) ? "REACHABLE" : "UNREACHABLE");
            if (isRunning) {
                state = "SYNCING";
            } else if (failures > 0) {
                state = "RETRYING";
                nextStep = lastError != null ? lastError.nextStep() : null;
            } else if (counts.pending() > 0) {
                state = "PENDING";
                nextStep = "Data is waiting to be sent. It is retried automatically every " + pollIntervalSeconds
                        + " seconds, or use Sync Now.";
            } else if (counts.synced() > 0) {
                state = "UP_TO_DATE";
            } else {
                state = "IDLE";
            }
        }

        SyncStatus.Queue queue = new SyncStatus.Queue(counts.pending(), counts.synced(), counts.failed(), counts.total(), counts.byType());
        return new SyncStatus(state, isRunning, cfg, connectivity, lastAttempt, queue, lastAttempt,
                st.get(K_LAST_ATTEMPT_RESULT), lastSuccess, failures, failures > 0 ? nextAttempt : null, lastError, nextStep);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static List<Long> ids(List<SyncRecord> records) {
        List<Long> ids = new ArrayList<>();
        for (SyncRecord r : records) {
            ids.add(r.outboxId());
        }
        return ids;
    }

    private static Instant parseInstant(String epochMillis) {
        if (epochMillis == null) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(epochMillis));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static SyncErrorCategory safeCategory(String name) {
        try {
            return SyncErrorCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            return SyncErrorCategory.INTERNAL_ERROR;
        }
    }
}
