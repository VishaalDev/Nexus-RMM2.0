package com.nexusrmm.sync;

import java.util.List;
import java.util.Set;

/**
 * The destination's acknowledgement for one batch.
 *
 * <p>Only record ids listed in {@code acknowledged} may ever be marked
 * synchronized. A record in neither list stays pending and is retried.
 */
public record SyncAck(String batchId, Set<String> acknowledged, List<Rejection> rejected) {

    /**
     * @param retryable false (default): the destination will never accept this record, so it is
     *                  marked FAILED and not resent. true: keep it pending and retry later.
     */
    public record Rejection(String recordId, String reason, boolean retryable) {
    }
}
