package com.nexusrmm.health.analysis;

import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.HealthStatus;

/** Maps a 0..100 score to a status using the configured cut-offs. */
public final class Scoring {

    private Scoring() {
    }

    public static HealthStatus statusFor(int score, HealthSettings settings) {
        var s = settings.scoring();
        if (score >= s.healthyMin()) return HealthStatus.HEALTHY;
        if (score >= s.fairMin()) return HealthStatus.FAIR;
        if (score >= s.degradedMin()) return HealthStatus.DEGRADED;
        return HealthStatus.CRITICAL;
    }
}
