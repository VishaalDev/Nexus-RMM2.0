package com.nexusrmm.health.analysis;

import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.provider.HealthDataProvider;

import java.time.Instant;

/** Everything one analysis run needs; {@code now} is fixed for the whole run so all analyzers agree on "recent". */
public final class AnalysisContext {

    private final HealthDataProvider data;
    private final HealthSettings settings;
    private final Instant now;

    public AnalysisContext(HealthDataProvider data, HealthSettings settings, Instant now) {
        this.data = data;
        this.settings = settings;
        this.now = now;
    }

    public HealthDataProvider data() {
        return data;
    }

    public HealthSettings settings() {
        return settings;
    }

    public Instant now() {
        return now;
    }

    public Instant shortStart() {
        return now.minus(settings.windows().shortWindow());
    }

    public Instant longStart() {
        return now.minus(settings.windows().longWindow());
    }

    public Instant eventStart() {
        return now.minus(settings.windows().eventWindow());
    }

    /** How many samples a fully-covered short window would hold at the configured collection interval. */
    public int expectedShortSamples() {
        long seconds = Math.max(1, settings.collectionInterval().toSeconds());
        return (int) Math.max(1, settings.windows().shortWindow().toSeconds() / seconds);
    }
}
