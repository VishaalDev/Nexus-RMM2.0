package com.nexusrmm.sync;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.nexusrmm.sync.SyncTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpSyncClientTest {

    private static SyncBatch batch(String... ids) {
        var records = new java.util.ArrayList<SyncRecord>();
        long n = 1;
        for (String id : ids) {
            var p = JsonNodeFactory.instance.objectNode();
            p.put("value", 1.5);
            records.add(new SyncRecord(n++, id, "METRIC_SAMPLE", p));
        }
        return new SyncBatch("batch-1", Instant.parse("2026-10-22T10:00:00Z"), new SyncDevice("pc-1", "HOST"), records);
    }

    private static SyncTransportException failure(String url, SyncSettings s, SyncBatch b) {
        return assertThrows(SyncTransportException.class, () -> new HttpSyncClient(s).send(b));
    }

    @Test
    void successfulAcknowledgementIsParsedAndAuthAndEnvelopeAreSent() throws Exception {
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 200,
                    "{\"batchId\":\"batch-1\",\"acknowledged\":[\"a\"],\"rejected\":[{\"recordId\":\"b\",\"reason\":\"bad\",\"retryable\":true},{\"recordId\":\"c\"}]}");
            SyncAck ack = new HttpSyncClient(settings(d.url(), 10)).send(batch("a", "b", "c"));
            assertEquals(java.util.Set.of("a"), ack.acknowledged());
            assertEquals(2, ack.rejected().size());
            assertTrue(ack.rejected().get(0).retryable());
            assertFalse(ack.rejected().get(1).retryable(), "rejections default to permanent");
            assertEquals("Bearer secret-token-123", d.authHeaders.get(0));
            var body = d.requestBodies.get(0);
            assertEquals(1, body.get("schemaVersion").asInt());
            assertEquals("batch-1", body.get("batchId").asText());
            assertEquals("pc-1", body.get("device").get("computerId").asText());
            assertEquals("a", body.get("records").get(0).get("recordId").asText());
        }
    }

    @Test
    void noAuthorizationHeaderWhenNoTokenConfigured() throws Exception {
        try (FakeDestination d = new FakeDestination()) {
            new HttpSyncClient(settingsWithTimeout(d.url(), Duration.ofSeconds(3))).send(batch("a"));
            assertNull(d.authHeaders.get(0));
        }
    }

    @Test
    void httpErrorsAreCategorizedAndNeverTreatedAsSuccess() throws Exception {
        int[][] cases = {{401, 0}, {403, 0}, {404, 1}, {405, 1}, {429, 2}, {500, 3}, {503, 3}, {400, 4}, {413, 4}, {422, 4}, {302, 5}};
        SyncErrorCategory[] expected = {SyncErrorCategory.AUTH_FAILED, SyncErrorCategory.ENDPOINT_NOT_FOUND,
                SyncErrorCategory.RATE_LIMITED, SyncErrorCategory.SERVER_ERROR, SyncErrorCategory.REQUEST_REJECTED,
                SyncErrorCategory.UNEXPECTED_HTTP_STATUS};
        try (FakeDestination d = new FakeDestination()) {
            for (int[] c : cases) {
                d.behavior = (ex, req, dest) -> dest.respond(ex, c[0], "{\"error\":\"x\"}");
                SyncTransportException e = failure(d.url(), settings(d.url(), 10), batch("a"));
                assertEquals(expected[c[1]], e.category(), "HTTP " + c[0]);
                assertEquals(c[0], e.httpStatus());
                assertTrue(e.destinationAnswered());
            }
        }
    }

    @Test
    void retryAfterSecondsIsCaptured() throws Exception {
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> {
                ex.getResponseHeaders().add("Retry-After", "42");
                dest.respond(ex, 429, "{}");
            };
            SyncTransportException e = failure(d.url(), settings(d.url(), 10), batch("a"));
            assertEquals(Duration.ofSeconds(42), e.retryAfter());
        }
    }

    @Test
    void invalidSuccessBodiesAreNotAcknowledgements() throws Exception {
        String[] bodies = {"", "not json", "[]", "{\"ok\":true}", "{\"acknowledged\":\"a\"}", "{\"acknowledged\":[1]}",
                "{\"batchId\":\"other\",\"acknowledged\":[\"a\"]}", "{\"acknowledged\":[],\"rejected\":{}}",
                "{\"acknowledged\":[],\"rejected\":[{\"reason\":\"no id\"}]}"};
        try (FakeDestination d = new FakeDestination()) {
            for (String b : bodies) {
                d.behavior = (ex, req, dest) -> dest.respond(ex, 200, b);
                SyncTransportException e = failure(d.url(), settings(d.url(), 10), batch("a"));
                assertEquals(SyncErrorCategory.INVALID_RESPONSE, e.category(), "body: '" + b + "'");
            }
            d.behavior = (ex, req, dest) -> dest.respond(ex, 204, "");
            assertEquals(SyncErrorCategory.INVALID_RESPONSE, failure(d.url(), settings(d.url(), 10), batch("a")).category());
        }
    }

    @Test
    void connectionRefusedIsAConnectionFailureNotAnHttpAnswer() throws Exception {
        String url = "http://127.0.0.1:" + freePortNotListening() + "/ingest";
        SyncTransportException e = failure(url, settings(url, 10), batch("a"));
        assertEquals(SyncErrorCategory.CONNECTION_FAILED, e.category());
        assertFalse(e.destinationAnswered());
        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().contains("secret-token-123"));
    }

    @Test
    void slowDestinationTimesOut() throws Exception {
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> {
                try {
                    Thread.sleep(3500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                try {
                    dest.respond(ex, 200, "{}");
                } catch (java.io.IOException ignored) {
                    // client already gave up
                }
            };
            long start = System.nanoTime();
            SyncTransportException e = failure(d.url(), settingsWithTimeout(d.url(), Duration.ofSeconds(1)), batch("a"));
            assertEquals(SyncErrorCategory.TIMEOUT, e.category());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3400, "must give up at the deadline");
        }
    }

    @Test
    void errorBodyExcerptNeverLeaksTheToken() throws Exception {
        try (FakeDestination d = new FakeDestination()) {
            d.behavior = (ex, req, dest) -> dest.respond(ex, 500, "bad token secret-token-123 echoed");
            SyncTransportException e = failure(d.url(), settings(d.url(), 10), batch("a"));
            assertFalse(e.getMessage().contains("secret-token-123"));
            assertTrue(e.getMessage().contains("***"));
        }
    }
}
