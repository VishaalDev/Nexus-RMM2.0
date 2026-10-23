package com.nexusrmm.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.nexusrmm.sync.SyncTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncCoordinatorTest {

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        ds = dataSource(newDbPath());
        createTelemetryTables(ds);
    }

    private SyncCoordinator coordinator(SyncSettings s) {
        SyncStore store = new SyncStore(ds, Clock.systemUTC());
        SyncEngine engine = new SyncEngine(store, new HttpSyncClient(s), s, () -> new SyncDevice("pc-1", "H"), Clock.systemUTC(), 10);
        SyncCoordinator c = new SyncCoordinator(engine, store, s);
        c.start();
        return c;
    }

    private static void waitUntil(java.util.function.BooleanSupplier cond) throws Exception {
        long deadline = System.currentTimeMillis() + 8000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(cond.getAsBoolean(), "condition not met in time");
    }

    @Test
    void manualTriggerWithoutDestinationReportsNotConfiguredAndDoesNothing() throws Exception {
        insertSamples(ds, 3, 1_700_000_000_000L);
        SyncCoordinator c = coordinator(settings(null, 10));
        SyncTriggerResponse r = c.trigger();
        assertEquals("NOT_CONFIGURED", r.outcome());
        assertEquals("NOT_CONFIGURED", r.status().state());
        assertEquals(0, r.status().queue().synced());
        assertEquals(3, r.status().queue().pending());
        c.tick(); // must be a no-op, not an error
        c.shutdown();
    }

    @Test
    void manualTriggerRunsInTheBackgroundAndStatusReflectsTheRealOutcome() throws Exception {
        insertSamples(ds, 5, 1_700_000_000_000L);
        try (FakeDestination d = new FakeDestination()) {
            SyncCoordinator c = coordinator(settings(d.url(), 100));
            SyncTriggerResponse r = c.trigger();
            assertEquals("STARTED", r.outcome());
            waitUntil(() -> {
                try {
                    return c.status().queue().synced() == 5 && !"SYNCING".equals(c.status().state());
                } catch (Exception e) {
                    return false;
                }
            });
            assertEquals("UP_TO_DATE", c.status().state());
            assertEquals(5, d.stored.size());
            c.shutdown();
        }
    }

    @Test
    void slowDestinationNeverBlocksTheCallerAndSecondTriggerIsRejected() throws Exception {
        insertSamples(ds, 2, 1_700_000_000_000L);
        CountDownLatch release = new CountDownLatch(1);
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> {
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                FakeDestination.ackAll(ex, req, dest);
            };
            SyncCoordinator c = coordinator(settings(d.url(), 100));

            long start = System.nanoTime();
            SyncTriggerResponse first = c.trigger();
            c.tick();
            SyncTriggerResponse second = c.trigger();
            long ms = (System.nanoTime() - start) / 1_000_000;

            assertEquals("STARTED", first.outcome());
            assertEquals("ALREADY_RUNNING", second.outcome());
            assertEquals("SYNCING", second.status().state());
            assertTrue(second.status().running());
            assertTrue(ms < 1500, "trigger/tick must return immediately while the destination hangs (took " + ms + " ms)");

            release.countDown();
            waitUntil(() -> {
                try {
                    return c.status().queue().synced() == 2;
                } catch (Exception e) {
                    return false;
                }
            });
            c.shutdown();
        }
    }
}
