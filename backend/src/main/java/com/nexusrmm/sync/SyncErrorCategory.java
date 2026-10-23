package com.nexusrmm.sync;

/**
 * Why a synchronization attempt failed, with an operator-facing next step.
 * Every category preserves the unacknowledged records and is retried with
 * backoff; the category only changes the guidance shown to the user.
 */
public enum SyncErrorCategory {
    CONNECTION_FAILED("The destination could not be reached. Check that the server is running, the host and port in "
            + "nexus.sync.destination-url are correct, and that this machine has network access. Data stays queued locally."),
    DNS_FAILURE("The destination host name could not be resolved. Check the host in nexus.sync.destination-url and your DNS/network connection."),
    TIMEOUT("The destination did not answer in time. It may be overloaded or unreachable; consider raising "
            + "nexus.sync.request-timeout or lowering nexus.sync.batch-size."),
    TLS_FAILURE("The TLS/SSL handshake failed. Check the destination certificate, the system clock, and that the URL scheme (http/https) matches the server."),
    AUTH_FAILED("The destination rejected the credentials (HTTP 401/403). Check nexus.sync.auth-token (NEXUS_SYNC_AUTH_TOKEN) and the destination's access rules."),
    ENDPOINT_NOT_FOUND("The destination answered 404/405. Check the path in nexus.sync.destination-url; it must be the endpoint that accepts POSTed batches."),
    RATE_LIMITED("The destination asked the client to slow down (HTTP 429). Retrying automatically with backoff."),
    SERVER_ERROR("The destination reported a server error (HTTP 5xx). Retrying automatically with backoff; check the destination's logs if it persists."),
    REQUEST_REJECTED("The destination rejected the whole batch as malformed or too large (HTTP 400/413/422). "
            + "Lower nexus.sync.batch-size, and check the destination implements the documented payload contract (docs/sync.md)."),
    UNEXPECTED_HTTP_STATUS("The destination answered with an unexpected HTTP status (for example a redirect). Check nexus.sync.destination-url points at the final endpoint."),
    INVALID_RESPONSE("The destination answered, but not with a valid acknowledgement body. Nothing was marked as synchronized. "
            + "Check the destination implements the documented acknowledgement contract (docs/sync.md)."),
    NOT_ACKNOWLEDGED("The destination answered but acknowledged none of the records in the batch. Nothing was marked as synchronized. "
            + "Check the destination's logs; records stay queued and are retried."),
    STORAGE_ERROR("The local sync queue could not be read or written. Local monitoring is unaffected; check the backend log and free disk space."),
    INTERNAL_ERROR("An unexpected error occurred during synchronization. Check the backend log. Data stays queued locally.");

    private final String nextStep;

    SyncErrorCategory(String nextStep) {
        this.nextStep = nextStep;
    }

    public String nextStep() {
        return nextStep;
    }
}
