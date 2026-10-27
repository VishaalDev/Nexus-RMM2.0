package com.nexusrmm.advisor;

import com.nexusrmm.advisor.config.AdvisorSettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvisorSettingsTest {

    private static AdvisorSettings with(int topN, double cpu, double mem, double dom, int uptime, int resolve, int max, int actions, Duration ttl) {
        return new AdvisorSettings(topN, cpu, mem, dom, uptime, resolve, max, actions, ttl);
    }

    @Test
    void defaultsAreValid() {
        AdvisorSettings s = AdvisorSettings.defaults();
        assertEquals(8, s.processTopN());
        assertEquals(3, s.topActions());
    }

    @Test
    void everyBoundIsEnforcedWithAnActionableMessage() {
        Duration ttl = Duration.ofSeconds(10);
        for (Runnable bad : new Runnable[] {
                () -> with(0, 5, 5, .5, 14, 2, 200, 3, ttl),
                () -> with(51, 5, 5, .5, 14, 2, 200, 3, ttl),
                () -> with(8, -1, 5, .5, 14, 2, 200, 3, ttl),
                () -> with(8, 5, 101, .5, 14, 2, 200, 3, ttl),
                () -> with(8, 5, 5, 0, 14, 2, 200, 3, ttl),
                () -> with(8, 5, 5, 1.5, 14, 2, 200, 3, ttl),
                () -> with(8, 5, 5, .5, 0, 2, 200, 3, ttl),
                () -> with(8, 5, 5, .5, 14, 0, 200, 3, ttl),
                () -> with(8, 5, 5, .5, 14, 2, 5, 3, ttl),
                () -> with(8, 5, 5, .5, 14, 2, 200, 0, ttl),
                () -> with(8, 5, 5, .5, 14, 2, 200, 3, Duration.ofSeconds(-1)),
                () -> with(8, 5, 5, .5, 14, 2, 200, 3, null)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, bad::run);
            assertTrue(e.getMessage().startsWith("Invalid nexus.advisor configuration"));
        }
    }
}
