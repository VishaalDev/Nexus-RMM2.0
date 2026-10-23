package com.nexusrmm.migration;

import com.nexusrmm.sync.SyncSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;

/**
 * Module 3: adds the durable synchronization queue ({@code sync_outbox}) and
 * its bookkeeping ({@code sync_state}).
 *
 * <p>Purely additive: two new tables and one index, created with
 * {@code IF NOT EXISTS}. No existing table, column or row is read, altered
 * or deleted, so this cannot affect historical telemetry. Existing rows are
 * picked up later, at runtime, by the queue's watermark logic - the
 * migration itself copies nothing. Applicable on a brand-new database too
 * (it depends on no other table).
 */
final class V4CreateSyncQueueTables implements SchemaMigrator.Migration {

    private static final Logger log = LoggerFactory.getLogger(V4CreateSyncQueueTables.class);

    @Override
    public int version() {
        return 4;
    }

    @Override
    public String description() {
        return "Create sync_outbox and sync_state tables for offline-first synchronization (additive)";
    }

    @Override
    public void apply(Connection connection) throws Exception {
        SyncSchema.apply(connection);
        log.info("Sync queue tables ready (sync_outbox, sync_state).");
    }
}
