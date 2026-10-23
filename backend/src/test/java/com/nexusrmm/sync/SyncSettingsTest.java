package com.nexusrmm.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncSettingsTest {

    private static SyncSettings of(String url, String token) {
        return SyncSettings.of(url, token, 200, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofMinutes(5), 2.0, 50, 5000, true);
    }

    @Test
    void blankOrMissingDestinationIsNotConfiguredAndNotAnError() {
        for (String url : new String[]{null, "", "   "}) {
            SyncSettings s = of(url, null);
            assertTrue(s.destinationMissing());
            assertFalse(s.configured());
            assertNull(s.issue(), "a missing destination is not a configuration error");
        }
    }

    @Test
    void validDestinationIsConfigured() {
        SyncSettings s = of("https://sync.example.org:8443/api/ingest?tenant=x", "tok");
        assertTrue(s.configured());
        assertEquals("https://sync.example.org:8443/api/ingest", s.displayDestination(), "query is never displayed");
        assertTrue(s.authConfigured());
    }

    @Test
    void invalidDestinationsAreReportedWithAReason() {
        assertTrue(of("ftp://host/x", null).issue().contains("http://"));
        assertTrue(of("not a url", null).issue().contains("not a valid URL"));
        assertTrue(of("http:///path", null).issue().contains("host"));
        assertFalse(of("ftp://host/x", null).configured());
        assertFalse(of("ftp://host/x", null).destinationMissing());
    }

    @Test
    void credentialsInTheUrlAreRejected() {
        SyncSettings s = of("https://user:pass@host/x", null);
        assertFalse(s.configured());
        assertTrue(s.issue().contains("must not contain credentials"));
        assertFalse(String.valueOf(s.displayDestination()).contains("pass"));
    }

    @Test
    void badTunablesAreReportedNotThrown() {
        SyncSettings s = SyncSettings.of("https://h/x", null, 0, Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofMinutes(10), Duration.ofMinutes(1), 0.5, 0, 0, true);
        assertFalse(s.configured());
        for (String fragment : new String[]{"batch-size", "connect-timeout", "backoff-initial", "backoff-multiplier",
                "max-batches-per-cycle", "enqueue-chunk-size"}) {
            assertTrue(s.issue().contains(fragment), fragment + " missing from: " + s.issue());
        }
    }

    @Test
    void tokenNeverAppearsInToStringAndPlainHttpToRemoteHostWarns() {
        SyncSettings s = of("http://remote.example.org/x", "super-secret");
        assertFalse(s.toString().contains("super-secret"));
        assertEquals(1, s.warnings().size());
        assertTrue(of("http://localhost:9000/x", "tok").warnings().isEmpty());
        assertTrue(of("https://remote.example.org/x", "tok").warnings().isEmpty());
    }
}
