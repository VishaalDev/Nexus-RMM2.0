package com.nexusrmm.sync;

/** Outcome of one {@link SyncEngine#runCycle(boolean)} call. */
public record SyncCycleResult(Outcome outcome, int attempted, int acknowledged, int rejected, int retained,
                              SyncErrorCategory errorCategory, String message) {

    public enum Outcome {
        /** No valid destination: nothing remote was attempted. */
        NOT_CONFIGURED,
        /** Another cycle was already running. */
        ALREADY_RUNNING,
        /** Scheduled tick skipped because the retry backoff has not elapsed. */
        BACKING_OFF,
        /** Configured, but no PENDING records existed. No request was made. */
        NOTHING_TO_SYNC,
        /** Every record sent was acknowledged (or permanently rejected by the destination). */
        SUCCESS,
        /** Some records were acknowledged; others remain pending (error or retryable rejections). */
        PARTIAL,
        /** No record was acknowledged. Everything attempted remains pending. */
        FAILED
    }

    static SyncCycleResult of(Outcome outcome, String message) {
        return new SyncCycleResult(outcome, 0, 0, 0, 0, null, message);
    }
}
