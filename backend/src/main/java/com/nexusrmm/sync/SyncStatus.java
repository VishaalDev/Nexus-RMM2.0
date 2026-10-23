package com.nexusrmm.sync;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API view of synchronization state (GET /api/sync/status). Contains no
 * credentials and no record data; queue information is counts only.
 */
public record SyncStatus(
        String state,
        boolean running,
        Configuration configuration,
        String connectivity,
        Instant connectivityCheckedAt,
        Queue queue,
        Instant lastAttemptAt,
        String lastAttemptResult,
        Instant lastSuccessfulSyncAt,
        int consecutiveFailures,
        Instant nextAttemptAt,
        LastError lastError,
        String nextStep) {

    public record Configuration(boolean configured, boolean destinationSet, String destination, boolean authConfigured,
                                String issue, List<String> warnings, int batchSize, int pollIntervalSeconds) {
    }

    public record Queue(long pending, long synced, long failed, long total, Map<String, SyncStore.TypeCounts> byType) {
    }

    public record LastError(String category, String message, Instant at, String nextStep) {
    }

    /** Same status, but reported as in progress (a cycle has been accepted and is starting or running). */
    public SyncStatus asSyncing() {
        return new SyncStatus("SYNCING", true, configuration, connectivity, connectivityCheckedAt, queue, lastAttemptAt,
                lastAttemptResult, lastSuccessfulSyncAt, consecutiveFailures, nextAttemptAt, lastError, null);
    }
}
