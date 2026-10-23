package com.nexusrmm.health.support;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.provider.BucketPoint;
import com.nexusrmm.health.provider.EventMonitoring;
import com.nexusrmm.health.provider.EventPoint;
import com.nexusrmm.health.provider.HealthDataProvider;
import com.nexusrmm.health.provider.LatestReading;
import com.nexusrmm.health.provider.SamplePoint;
import com.nexusrmm.provider.AvailabilityStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

/**
 * In-memory {@link HealthDataProvider}. Raw samples and bucket aggregates
 * are both derived from the same stored rows, exactly as the real
 * repository derives them from metric_sample, so a test scenario is
 * internally consistent across the short and long views.
 */
public final class FakeHealthData implements HealthDataProvider {

    public static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    public static final Duration STEP = Duration.ofSeconds(10);

    private record Key(MetricType type, String instance) {
    }

    private record Row(Instant time, Double value, AvailabilityStatus status, String diagnostic) {
    }

    private final Map<Key, List<Row>> rows = new HashMap<>();
    private final List<EventPoint> events = new ArrayList<>();
    private EventMonitoring monitoring = new EventMonitoring(true, true, NOW, null);

    public FakeHealthData values(MetricType type, String instance, Instant from, Instant to, Duration step, ToDoubleFunction<Instant> f) {
        List<Row> list = rows.computeIfAbsent(new Key(type, instance), k -> new ArrayList<>());
        for (Instant t = from; t.isBefore(to); t = t.plus(step)) {
            list.add(new Row(t, f.applyAsDouble(t), AvailabilityStatus.AVAILABLE, null));
        }
        return this;
    }

    /** Valueless rows (UNAVAILABLE / UNSUPPORTED / NOT_APPLICABLE ...) carrying the provider's diagnostic. */
    public FakeHealthData valueless(MetricType type, String instance, Instant from, Instant to, Duration step,
                                    AvailabilityStatus status, String diagnostic) {
        List<Row> list = rows.computeIfAbsent(new Key(type, instance), k -> new ArrayList<>());
        for (Instant t = from; t.isBefore(to); t = t.plus(step)) {
            list.add(new Row(t, null, status, diagnostic));
        }
        return this;
    }

    /** The last hour at the 10 s collection interval, constant. */
    public FakeHealthData recentConstant(MetricType type, String instance, double value) {
        return values(type, instance, NOW.minus(Duration.ofHours(1)), NOW, STEP, t -> value);
    }

    /** History from {@code daysBack} days ago up to one hour ago (the short window starts there), every {@code step}. */
    public FakeHealthData history(MetricType type, String instance, int daysBack, Duration step, ToDoubleFunction<Instant> f) {
        return values(type, instance, NOW.minus(Duration.ofDays(daysBack)), NOW.minus(Duration.ofHours(1)), step, f);
    }

    public FakeHealthData event(Duration ago, String category, String level) {
        events.add(new EventPoint(NOW.minus(ago), category, level, "test-provider", 1));
        events.sort(Comparator.comparing(EventPoint::time).reversed());
        return this;
    }

    public FakeHealthData monitoring(EventMonitoring m) {
        this.monitoring = m;
        return this;
    }

    /** Hours elapsed since {@code from}, for building linear series. */
    public static double hours(Instant from, Instant t) {
        return Duration.between(from, t).toMillis() / 3_600_000.0;
    }

    // ---- HealthDataProvider -------------------------------------------------

    @Override
    public String computerId() {
        return "test-computer";
    }

    @Override
    public List<String> instances(MetricType type, Instant since) {
        TreeSet<String> out = new TreeSet<>();
        rows.forEach((k, v) -> {
            if (k.type() == type && k.instance() != null && v.stream().anyMatch(r -> !r.time().isBefore(since))) {
                out.add(k.instance());
            }
        });
        return new ArrayList<>(out);
    }

    @Override
    public List<SamplePoint> samples(MetricType type, String instance, Instant from, int limit) {
        return rows.getOrDefault(new Key(type, instance), List.of()).stream()
                .filter(r -> !r.time().isBefore(from) && !r.time().isAfter(NOW))
                .sorted(Comparator.comparing(Row::time))
                .limit(limit)
                .map(r -> new SamplePoint(r.time(), r.value(), r.status()))
                .toList();
    }

    @Override
    public List<BucketPoint> buckets(MetricType type, String instance, Instant from, Instant to, Duration bucket) {
        long bucketMs = bucket.toMillis();
        TreeMap<Long, List<Row>> grouped = new TreeMap<>();
        for (Row r : rows.getOrDefault(new Key(type, instance), List.of())) {
            long ms = r.time().toEpochMilli();
            if (ms >= from.toEpochMilli() && ms < to.toEpochMilli()) {
                grouped.computeIfAbsent((ms / bucketMs) * bucketMs, k -> new ArrayList<>()).add(r);
            }
        }
        List<BucketPoint> out = new ArrayList<>();
        grouped.forEach((start, list) -> {
            double[] v = list.stream().filter(r -> r.value() != null).mapToDouble(Row::value).toArray();
            out.add(new BucketPoint(Instant.ofEpochMilli(start),
                    v.length == 0 ? 0 : java.util.Arrays.stream(v).average().orElse(0),
                    v.length == 0 ? 0 : java.util.Arrays.stream(v).min().orElse(0),
                    v.length == 0 ? 0 : java.util.Arrays.stream(v).max().orElse(0),
                    list.size(), v.length));
        });
        return out;
    }

    @Override
    public Optional<LatestReading> latest(MetricType type, String instance) {
        return rows.getOrDefault(new Key(type, instance), List.of()).stream()
                .max(Comparator.comparing(Row::time))
                .map(r -> new LatestReading(r.time(), r.status(), r.value(), r.diagnostic()));
    }

    @Override
    public List<EventPoint> events(Instant since, int limit) {
        return events.stream().filter(e -> !e.time().isBefore(since)).limit(limit).toList();
    }

    @Override
    public EventMonitoring eventMonitoring() {
        return monitoring;
    }
}
