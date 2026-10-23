package com.nexusrmm.sync;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable, validated view of {@code nexus.sync.*}.
 *
 * <p>An invalid or missing destination never stops the application from
 * starting: local monitoring must keep working regardless of sync
 * configuration. Instead {@link #issue()} describes what is wrong and the
 * sync engine reports {@code NOT_CONFIGURED} / {@code CONFIG_INVALID} while
 * doing nothing remote.
 *
 * <p>The auth token lives only in this object (and in the outgoing
 * {@code Authorization} header). It is never part of any DTO, log line or
 * {@link #toString()}.
 */
public record SyncSettings(
        String rawDestination,
        URI destination,
        String issue,
        List<String> warnings,
        String authToken,
        int batchSize,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration backoffInitial,
        Duration backoffMax,
        double backoffMultiplier,
        int maxBatchesPerCycle,
        int enqueueChunkSize,
        boolean backfillExistingData) {

    public static final int MAX_BATCH_SIZE = 1000;

    /** True when the destination is absent (distinct from present-but-invalid). */
    public boolean destinationMissing() {
        return rawDestination == null || rawDestination.isBlank();
    }

    /** True only when a valid destination and valid tunables are present. */
    public boolean configured() {
        return !destinationMissing() && issue == null && destination != null;
    }

    public boolean authConfigured() {
        return authToken != null && !authToken.isBlank();
    }

    /** scheme://host[:port]/path - never user-info, query or fragment. */
    public String displayDestination() {
        if (destination == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(destination.getScheme()).append("://").append(destination.getHost());
        if (destination.getPort() != -1) {
            sb.append(':').append(destination.getPort());
        }
        String path = destination.getRawPath();
        if (path != null) {
            sb.append(path);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "SyncSettings[destination=" + displayDestination() + ", authConfigured=" + authConfigured()
                + ", batchSize=" + batchSize + ", issue=" + issue + "]";
    }

    public static SyncSettings of(String destinationUrl,
                                  String authToken,
                                  int batchSize,
                                  Duration connectTimeout,
                                  Duration requestTimeout,
                                  Duration backoffInitial,
                                  Duration backoffMax,
                                  double backoffMultiplier,
                                  int maxBatchesPerCycle,
                                  int enqueueChunkSize,
                                  boolean backfillExistingData) {
        String raw = destinationUrl == null ? null : destinationUrl.trim();
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        URI uri = null;

        if (raw != null && !raw.isEmpty()) {
            try {
                uri = new URI(raw);
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    problems.add("nexus.sync.destination-url must start with http:// or https://");
                    uri = null;
                } else if (uri.getHost() == null || uri.getHost().isBlank()) {
                    problems.add("nexus.sync.destination-url has no host name");
                    uri = null;
                } else if (uri.getUserInfo() != null) {
                    problems.add("nexus.sync.destination-url must not contain credentials; "
                            + "use nexus.sync.auth-token (or the NEXUS_SYNC_AUTH_TOKEN environment variable) instead");
                    uri = null;
                } else if (uri.getFragment() != null) {
                    problems.add("nexus.sync.destination-url must not contain a #fragment");
                    uri = null;
                }
            } catch (Exception e) {
                problems.add("nexus.sync.destination-url is not a valid URL");
                uri = null;
            }
        }

        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            problems.add("nexus.sync.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        if (isNotPositive(connectTimeout)) {
            problems.add("nexus.sync.connect-timeout must be a positive duration (e.g. PT5S)");
        }
        if (isNotPositive(requestTimeout)) {
            problems.add("nexus.sync.request-timeout must be a positive duration (e.g. PT30S)");
        }
        if (isNotPositive(backoffInitial) || isNotPositive(backoffMax)) {
            problems.add("nexus.sync.backoff-initial and nexus.sync.backoff-max must be positive durations");
        } else if (backoffInitial.compareTo(backoffMax) > 0) {
            problems.add("nexus.sync.backoff-initial must not exceed nexus.sync.backoff-max");
        }
        if (!(backoffMultiplier >= 1.0 && backoffMultiplier <= 10.0)) {
            problems.add("nexus.sync.backoff-multiplier must be between 1.0 and 10.0");
        }
        if (maxBatchesPerCycle < 1) {
            problems.add("nexus.sync.max-batches-per-cycle must be at least 1");
        }
        if (enqueueChunkSize < 1) {
            problems.add("nexus.sync.enqueue-chunk-size must be at least 1");
        }

        boolean hasToken = authToken != null && !authToken.isBlank();
        if (uri != null && hasToken && "http".equalsIgnoreCase(uri.getScheme()) && !isLoopback(uri.getHost())) {
            warnings.add("The auth token will be sent over plain HTTP to a non-local host. Use https:// for any real destination.");
        }

        String issue = problems.isEmpty() ? null : String.join("; ", problems);
        return new SyncSettings(raw, uri, issue, List.copyOf(warnings), hasToken ? authToken : null,
                batchSize,
                connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout,
                requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout,
                backoffInitial == null ? Duration.ofSeconds(5) : backoffInitial,
                backoffMax == null ? Duration.ofMinutes(5) : backoffMax,
                backoffMultiplier, maxBatchesPerCycle, enqueueChunkSize, backfillExistingData);
    }

    private static boolean isNotPositive(Duration d) {
        return d == null || d.isZero() || d.isNegative();
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
    }
}
