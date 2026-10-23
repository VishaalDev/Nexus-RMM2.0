package com.nexusrmm.sync;

/**
 * Result of POST /api/sync/trigger. {@code outcome}:
 * STARTED (accepted, runs in the background), ALREADY_RUNNING,
 * NOT_CONFIGURED or CONFIG_INVALID. The embedded status is the real current state.
 */
public record SyncTriggerResponse(String outcome, String message, SyncStatus status) {
}
