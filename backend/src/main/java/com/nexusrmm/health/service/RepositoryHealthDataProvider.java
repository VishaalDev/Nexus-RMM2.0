package com.nexusrmm.health.service;

import com.nexusrmm.entity.MetricType;
import com.nexusrmm.health.provider.BucketPoint;
import com.nexusrmm.health.provider.EventMonitoring;
import com.nexusrmm.health.provider.EventPoint;
import com.nexusrmm.health.provider.HealthDataProvider;
import com.nexusrmm.health.provider.LatestReading;
import com.nexusrmm.health.provider.SamplePoint;
import com.nexusrmm.repository.HardwareEventRepository;
import com.nexusrmm.repository.MetricSampleRepository;
import com.nexusrmm.service.DeviceIdentityService;
import com.nexusrmm.service.WindowsEventLogService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Production {@link HealthDataProvider}: reads Module 1's stored telemetry
 * through its repositories and its event-monitor status through
 * WindowsEventLogService. Purely a mapping layer - no analysis logic - so
 * everything interesting stays testable without a database.
 */
@Component
public class RepositoryHealthDataProvider implements HealthDataProvider {

    private final MetricSampleRepository metrics;
    private final HardwareEventRepository events;
    private final DeviceIdentityService identity;
    private final WindowsEventLogService eventLog;

    public RepositoryHealthDataProvider(MetricSampleRepository metrics, HardwareEventRepository events,
                                        DeviceIdentityService identity, WindowsEventLogService eventLog) {
        this.metrics = metrics;
        this.events = events;
        this.identity = identity;
        this.eventLog = eventLog;
    }

    @Override
    public String computerId() {
        return identity.getCurrent().getComputerId();
    }

    @Override
    public List<String> instances(MetricType type, Instant since) {
        return metrics.findDistinctInstanceLabels(computerId(), type, since).stream()
                .filter(Objects::nonNull).sorted().toList();
    }

    @Override
    public List<SamplePoint> samples(MetricType type, String instance, Instant from, int limit) {
        return metrics.findHistory(computerId(), type, instance, from, PageRequest.of(0, limit)).stream()
                .map(s -> new SamplePoint(s.getTimestamp(), s.getValue(), s.getAvailabilityStatus()))
                .toList();
    }

    @Override
    public List<BucketPoint> buckets(MetricType type, String instance, Instant from, Instant to, Duration bucket) {
        long bucketMs = Math.max(1, bucket.toMillis());
        List<MetricSampleRepository.BucketRow> rows = instance == null
                ? metrics.bucketsForSingleInstance(computerId(), type.name(), bucketMs, from.toEpochMilli(), to.toEpochMilli())
                : metrics.bucketsForInstance(computerId(), type.name(), instance, bucketMs, from.toEpochMilli(), to.toEpochMilli());
        return rows.stream().map(r -> new BucketPoint(
                Instant.ofEpochMilli(r.getBucketStartMs().longValue()),
                num(r.getAvgValue()), num(r.getMinValue()), num(r.getMaxValue()),
                r.getSampleCount().intValue(), r.getValueCount().intValue())).toList();
    }

    @Override
    public Optional<LatestReading> latest(MetricType type, String instance) {
        return metrics.findLatest(computerId(), type, instance, PageRequest.of(0, 1)).stream().findFirst()
                .map(s -> new LatestReading(s.getTimestamp(), s.getAvailabilityStatus(), s.getValue(), s.getDiagnostic()));
    }

    @Override
    public List<EventPoint> events(Instant since, int limit) {
        return events.findSince(computerId(), since, PageRequest.of(0, limit)).stream()
                .map(e -> new EventPoint(e.getOccurredAt(), e.getCategory(), e.getLevel(), e.getProviderName(), e.getWindowsEventId()))
                .toList();
    }

    @Override
    public EventMonitoring eventMonitoring() {
        var s = eventLog.lastStatus();
        return new EventMonitoring(s.applicable(), s.lastPollSuccess(), s.lastPollAt(), s.lastError());
    }

    private static double num(Number n) {
        return n == null ? 0.0 : n.doubleValue();
    }
}
