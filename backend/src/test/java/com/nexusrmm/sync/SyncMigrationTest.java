package com.nexusrmm.sync;

import com.nexusrmm.migration.SchemaMigrator;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static com.nexusrmm.sync.SyncTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Migration V4 is additive: existing telemetry is untouched and re-running it is harmless. */
class SyncMigrationTest {

    @Test
    void v4AddsQueueTablesWithoutTouchingExistingTelemetryAndIsIdempotent() throws Exception {
        Path db = newDbPath();
        DataSource ds = dataSource(db);
        createTelemetryTables(ds);
        insertSamples(ds, 25, 1_700_000_000_000L);
        insertEvent(ds, 1_700_000_999_000L);
        long samplesBefore = scalar(ds, "SELECT COUNT(*) FROM metric_sample");
        long sumBefore = scalar(ds, "SELECT SUM(timestamp) FROM metric_sample");

        SchemaMigrator.migrate(db.toString());

        assertEquals(samplesBefore, scalar(ds, "SELECT COUNT(*) FROM metric_sample"));
        assertEquals(sumBefore, scalar(ds, "SELECT SUM(timestamp) FROM metric_sample"));
        assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM hardware_event"));
        assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'sync_outbox'"));
        assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'sync_state'"));
        assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM schema_migration WHERE version = 4"));
        assertEquals(0, scalar(ds, "SELECT COUNT(*) FROM sync_outbox"), "the migration itself queues nothing");

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'sync_outbox'")) {
            rs.next();
            assertTrue(!rs.getString(1).toUpperCase().contains("CHECK"), "no CHECK constraints (see docs/database.md)");
        }

        SchemaMigrator.migrate(db.toString()); // second start
        assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM schema_migration WHERE version = 4"));
        assertEquals(samplesBefore, scalar(ds, "SELECT COUNT(*) FROM metric_sample"));
    }

    @Test
    void v4WorksOnABrandNewDatabase() throws Exception {
        Path db = newDbPath();
        SchemaMigrator.migrate(db.toString());
        DataSource ds = dataSource(db);
        assertEquals(1, scalar(ds, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'sync_outbox'"));
    }
}
