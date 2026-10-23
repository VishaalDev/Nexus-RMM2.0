package com.nexusrmm.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs {@link SyncEngine} cycles on a dedicated single background thread.
 *
 * <p>This is what keeps monitoring alive during sync problems: the shared
 * Spring scheduler thread that also runs metric collection only ever
 * enqueues work here and returns immediately, so a slow or hanging
 * destination can never delay a collection cycle.
 */
public final class SyncCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinator.class);

    private final SyncEngine engine;
    private final SyncStore store;
    private final SyncSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexus-sync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean cycleQueued = new AtomicBoolean(false);

    public SyncCoordinator(SyncEngine engine, SyncStore store, SyncSettings settings) {
        this.engine = engine;
        this.store = store;
        this.settings = settings;
    }

    /** Verifies/creates the queue tables and initializes watermarks. Failure is logged, never fatal. */
    public void start() {
        try {
            store.ensureSchema();
            store.initializeCursors(settings.backfillExistingData());
            log.info("Synchronization: {}", settings.configured()
                    ? "destination " + settings.displayDestination()
                    : settings.destinationMissing() ? "NOT_CONFIGURED (local-only mode)" : "CONFIG_INVALID: " + settings.issue());
        } catch (SQLException e) {
            log.error("Synchronization queue could not be initialized; local monitoring is unaffected", e);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Periodic automatic attempt. Never blocks the caller. */
    public void tick() {
        if (!settings.configured()) {
            return;
        }
        submit(false);
    }

    /** Manual "Sync Now". Never blocks the caller; ignores retry backoff. */
    public SyncTriggerResponse trigger() throws SQLException {
        if (settings.destinationMissing()) {
            return new SyncTriggerResponse("NOT_CONFIGURED",
                    "No synchronization destination is configured; nothing was sent.", status());
        }
        if (!settings.configured()) {
            return new SyncTriggerResponse("CONFIG_INVALID",
                    "Synchronization configuration is invalid: " + settings.issue(), status());
        }
        if (!submit(true)) {
            return new SyncTriggerResponse("ALREADY_RUNNING", "A synchronization is already in progress.", status());
        }
        return new SyncTriggerResponse("STARTED", "Synchronization started.", status());
    }

    public SyncStatus status() throws SQLException {
        SyncStatus s = engine.status();
        if (settings.configured() && cycleQueued.get() && !"SYNCING".equals(s.state())) {
            return s.asSyncing();
        }
        return s;
    }

    private boolean submit(boolean manual) {
        if (!cycleQueued.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    engine.runCycle(manual);
                } catch (RuntimeException e) {
                    log.error("Synchronization cycle crashed", e);
                } finally {
                    cycleQueued.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            cycleQueued.set(false);
            return false;
        }
    }
}
