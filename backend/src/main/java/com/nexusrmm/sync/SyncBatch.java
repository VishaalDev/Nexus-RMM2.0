package com.nexusrmm.sync;

import java.time.Instant;
import java.util.List;

/** A bounded group of records sent in one HTTP request. */
public record SyncBatch(String batchId, Instant sentAt, SyncDevice device, List<SyncRecord> records) {
}
