package com.nexusrmm.sync;

import java.time.Duration;

/**
 * A failed delivery attempt: nothing in the batch may be treated as
 * acknowledged. {@code httpStatus} is non-null when the destination actually
 * answered (used to distinguish "unreachable" from "reachable but erroring").
 */
public class SyncTransportException extends Exception {

    private static final long serialVersionUID = 1L;

    private final SyncErrorCategory category;
    private final Integer httpStatus;
    private final Duration retryAfter;

    public SyncTransportException(SyncErrorCategory category, String message) {
        this(category, message, null, null, null);
    }

    public SyncTransportException(SyncErrorCategory category, String message, Integer httpStatus, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public SyncErrorCategory category() {
        return category;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** True when the destination produced an HTTP response (so the network path works). */
    public boolean destinationAnswered() {
        return httpStatus != null;
    }
}
