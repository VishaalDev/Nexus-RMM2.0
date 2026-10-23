package com.nexusrmm.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.nio.channels.UnresolvedAddressException;

/**
 * JSON-over-HTTP delivery using the JDK's built-in client (no new
 * dependency). One POST per batch; the contract is documented in
 * docs/sync.md.
 *
 * <p>Success is declared only when the destination answers 2xx <em>and</em>
 * returns a well-formed acknowledgement. Redirects are never followed (a
 * redirected POST could leak the Authorization header).
 */
public final class HttpSyncClient implements SyncClient {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    private final SyncSettings settings;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpSyncClient(SyncSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public SyncAck send(SyncBatch batch) throws SyncTransportException {
        if (settings.destination() == null) {
            throw new SyncTransportException(SyncErrorCategory.INTERNAL_ERROR, "No valid destination configured");
        }
        byte[] body = serialize(batch);

        HttpRequest.Builder builder = HttpRequest.newBuilder(settings.destination())
                .timeout(settings.requestTimeout())
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .header("User-Agent", "Nexus-RMM-Sync/1")
                .header("X-Nexus-Schema-Version", Integer.toString(SCHEMA_VERSION))
                .header("X-Nexus-Batch-Id", batch.batchId())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (settings.authConfigured()) {
            builder.header("Authorization", "Bearer " + settings.authToken());
        }

        CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            // The overall deadline covers the whole exchange, including reading the body.
            response = future.get(settings.requestTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SyncTransportException(SyncErrorCategory.TIMEOUT,
                    "No complete response from " + settings.displayDestination() + " within " + settings.requestTimeout().toSeconds() + "s");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SyncTransportException(SyncErrorCategory.INTERNAL_ERROR, "Synchronization was interrupted");
        } catch (ExecutionException e) {
            throw mapIoFailure(e.getCause() == null ? e : e.getCause());
        }

        int status = response.statusCode();
        byte[] responseBody = response.body() == null ? new byte[0] : response.body();
        if (status >= 200 && status < 300) {
            return parseAck(batch, status, responseBody);
        }
        throw mapHttpFailure(status, response, responseBody);
    }

    // ---- request ----------------------------------------------------------------------

    private byte[] serialize(SyncBatch batch) throws SyncTransportException {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("batchId", batch.batchId());
        root.put("sentAt", batch.sentAt().toString());
        ObjectNode device = root.putObject("device");
        device.put("computerId", batch.device().computerId());
        if (batch.device().hostname() == null) {
            device.putNull("hostname");
        } else {
            device.put("hostname", batch.device().hostname());
        }
        ArrayNode records = root.putArray("records");
        for (SyncRecord r : batch.records()) {
            ObjectNode node = records.addObject();
            node.put("recordId", r.recordId());
            node.put("type", r.type());
            node.set("payload", r.payload());
        }
        try {
            return mapper.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new SyncTransportException(SyncErrorCategory.INTERNAL_ERROR, "Could not serialize batch: " + e.getMessage());
        }
    }

    // ---- response ---------------------------------------------------------------------

    private SyncAck parseAck(SyncBatch batch, int status, byte[] body) throws SyncTransportException {
        if (body.length == 0) {
            throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                    "Destination answered HTTP " + status + " with an empty body; an acknowledgement body is required", status, null, null);
        }
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                    "Acknowledgement body exceeds " + MAX_RESPONSE_BYTES + " bytes", status, null, null);
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                    "Destination answered HTTP " + status + " but the body is not valid JSON", status, null, null);
        }
        if (root == null || !root.isObject() || !root.has("acknowledged") || !root.get("acknowledged").isArray()) {
            throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                    "Destination answered HTTP " + status + " but the body has no 'acknowledged' array", status, null, null);
        }
        JsonNode echoed = root.get("batchId");
        if (echoed != null && !echoed.isNull() && !batch.batchId().equals(echoed.asText())) {
            throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                    "Acknowledgement belongs to a different batch (batchId mismatch)", status, null, null);
        }

        Set<String> acknowledged = new HashSet<>();
        for (JsonNode n : root.get("acknowledged")) {
            if (!n.isTextual()) {
                throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                        "'acknowledged' must contain only record id strings", status, null, null);
            }
            acknowledged.add(n.asText());
        }
        List<SyncAck.Rejection> rejected = new ArrayList<>();
        JsonNode rej = root.get("rejected");
        if (rej != null && !rej.isNull()) {
            if (!rej.isArray()) {
                throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                        "'rejected' must be an array", status, null, null);
            }
            for (JsonNode n : rej) {
                JsonNode id = n.get("recordId");
                if (id == null || !id.isTextual()) {
                    throw new SyncTransportException(SyncErrorCategory.INVALID_RESPONSE,
                            "Every 'rejected' entry needs a string recordId", status, null, null);
                }
                String reason = n.hasNonNull("reason") ? n.get("reason").asText() : "Rejected by destination";
                boolean retryable = n.hasNonNull("retryable") && n.get("retryable").asBoolean(false);
                rejected.add(new SyncAck.Rejection(id.asText(), reason, retryable));
            }
        }
        return new SyncAck(batch.batchId(), acknowledged, rejected);
    }

    private SyncTransportException mapHttpFailure(int status, HttpResponse<byte[]> response, byte[] body) {
        Duration retryAfter = response.headers().firstValue("Retry-After").map(HttpSyncClient::parseRetryAfter).orElse(null);
        String excerpt = excerpt(body);
        String suffix = excerpt.isEmpty() ? "" : " - " + excerpt;
        SyncErrorCategory category;
        if (status == 401 || status == 403) {
            category = SyncErrorCategory.AUTH_FAILED;
        } else if (status == 404 || status == 405) {
            category = SyncErrorCategory.ENDPOINT_NOT_FOUND;
        } else if (status == 429) {
            category = SyncErrorCategory.RATE_LIMITED;
        } else if (status == 408 || status == 425 || status >= 500) {
            category = status == 408 ? SyncErrorCategory.TIMEOUT : SyncErrorCategory.SERVER_ERROR;
        } else if (status == 400 || status == 413 || status == 422) {
            category = SyncErrorCategory.REQUEST_REJECTED;
        } else {
            category = SyncErrorCategory.UNEXPECTED_HTTP_STATUS;
        }
        return new SyncTransportException(category, "Destination answered HTTP " + status + suffix, status, retryAfter, null);
    }

    private SyncTransportException mapIoFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpTimeoutException) {
                return new SyncTransportException(SyncErrorCategory.TIMEOUT,
                        "Timed out talking to " + settings.displayDestination(), null, null, t);
            }
            if (c instanceof UnknownHostException || c instanceof UnresolvedAddressException) {
                return new SyncTransportException(SyncErrorCategory.DNS_FAILURE,
                        "Could not resolve the host of " + settings.displayDestination(), null, null, t);
            }
            if (c instanceof SSLException) {
                return new SyncTransportException(SyncErrorCategory.TLS_FAILURE,
                        "TLS handshake with " + settings.displayDestination() + " failed", null, null, t);
            }
            if (c instanceof ConnectException) {
                return new SyncTransportException(SyncErrorCategory.CONNECTION_FAILED,
                        "Could not connect to " + settings.displayDestination(), null, null, t);
            }
        }
        return new SyncTransportException(SyncErrorCategory.CONNECTION_FAILED,
                "Connection to " + settings.displayDestination() + " failed (" + t.getClass().getSimpleName() + ")", null, null, t);
    }

    private static Duration parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return null; // HTTP-date form is ignored; normal backoff applies
        }
    }

    private String excerpt(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8)
                .replaceAll("[\\p{Cntrl}]+", " ").trim();
        if (settings.authConfigured()) {
            text = text.replace(settings.authToken(), "***");
        }
        return text;
    }
}
