package com.nexusrmm.health.analysis;

import com.nexusrmm.health.config.HealthSettings;
import com.nexusrmm.health.model.ComponentHealth;
import com.nexusrmm.health.model.Finding;
import com.nexusrmm.health.model.InstanceHealth;
import com.nexusrmm.health.support.FakeHealthData;

import java.time.Duration;
import java.time.Instant;

final class TestSupport {

    static final Instant NOW = FakeHealthData.NOW;
    static final Instant WEEK_AGO = NOW.minus(Duration.ofDays(7));
    static final Duration FIVE_MIN = Duration.ofMinutes(5);

    private TestSupport() {
    }

    static AnalysisContext ctx(FakeHealthData data) {
        return new AnalysisContext(data, HealthSettings.defaults(), NOW);
    }

    static boolean hasFinding(ComponentHealth c, String code) {
        return c.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    static Finding finding(ComponentHealth c, String code) {
        return c.findings().stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow(
                () -> new AssertionError("expected finding " + code + " but had " + c.findings().stream().map(Finding::code).toList()));
    }

    static InstanceHealth instance(ComponentHealth c, String label) {
        return c.instances().stream().filter(i -> java.util.Objects.equals(i.instance(), label)).findFirst().orElseThrow();
    }

    /** Value rising linearly from {@code start} at {@code perDay} per day, anchored at WEEK_AGO. */
    static java.util.function.ToDoubleFunction<Instant> linearPerDay(double start, double perDay) {
        return t -> start + perDay * (FakeHealthData.hours(WEEK_AGO, t) / 24.0);
    }
}
