package com.nexusrmm.sync;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One locally stored row prepared for delivery.
 *
 * @param outboxId local queue row id (never sent)
 * @param recordId stable, globally unique idempotency key:
 *                 {@code <computerId>/<TYPE>/<localRowId>}; identical on every retry
 * @param type     {@code METRIC_SAMPLE} or {@code HARDWARE_EVENT}
 * @param payload  the original stored values, unmodified (nulls preserved as JSON null)
 */
public record SyncRecord(long outboxId, String recordId, String type, ObjectNode payload) {
}
