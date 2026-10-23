package com.nexusrmm.sync;

import java.sql.Connection;
import java.sql.Statement;

/**
 * DDL for the two additive tables Module 3 introduces. Statements are
 * idempotent ({@code IF NOT EXISTS}) so they are safe both in migration V4
 * and as a startup re-verification. No existing table is touched, and no
 * CHECK constraints are used (see docs/database.md for why).
 */
public final class SyncSchema {

    private SyncSchema() {
    }

    public static final String[] STATEMENTS = {
            """
            CREATE TABLE IF NOT EXISTS sync_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                record_type TEXT NOT NULL,
                source_id INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                enqueued_at INTEGER NOT NULL,
                last_attempt_at INTEGER,
                synced_at INTEGER,
                last_error TEXT,
                UNIQUE (record_type, source_id)
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_sync_outbox_status ON sync_outbox (status, id)",
            """
            CREATE TABLE IF NOT EXISTS sync_state (
                state_key TEXT PRIMARY KEY,
                state_value TEXT,
                updated_at INTEGER NOT NULL
            )
            """
    };

    public static void apply(Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            for (String sql : STATEMENTS) {
                st.execute(sql);
            }
        }
    }
}
