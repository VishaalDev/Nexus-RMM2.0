package com.nexusrmm.service;

import com.nexusrmm.config.NexusProperties;
import com.nexusrmm.dto.CurrentMetricDto;
import com.nexusrmm.dto.HistoryPointDto;
import com.nexusrmm.entity.MetricSample;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.repository.MetricSampleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of Module 1's stored telemetry. Kept separate from the
 * collector (write side) so future read paths — e.g. Module 2's analysis
 * pipeline pulling history for trend/anomaly calculations — depend on this
 * service rather than reaching into the repository directly.
 */
@Service
public class MetricQueryService {

    private final MetricSampleRepository metricSampleRepository;
    private final DeviceIdentityService deviceIdentityService;
    private final NexusProperties properties;

    public MetricQueryService(MetricSampleRepository metricSampleRepository,
                               DeviceIdentityService deviceIdentityService,
                               NexusProperties properties) {
        this.metricSampleRepository = metricSampleRepository;
        this.deviceIdentityService = deviceIdentityService;
        this.properties = properties;
    }

    /**
     * Latest sample per instance for a metric type, within a short
     * lookback window (a few collection intervals). A metric type with
     * several live instances (disks, NICs, GPUs) returns several rows;
     * one with a single instance returns at most one.
     */
    public List<CurrentMetricDto> currentValues(MetricType type) {
        String computerId = deviceIdentityService.getCurrent().getComputerId();
        int lookbackSeconds = properties.getCollection().getIntervalSeconds()
                * Math.max(1, properties.getHistory().getCurrentValueLookbackMultiplier());
        Instant since = Instant.now().minusSeconds(lookbackSeconds);

        List<MetricSample> recent = metricSampleRepository.findRecent(computerId, type, since);

        // recent is already ordered newest-first; keep only the first
        // occurrence of each instance (null instanceLabel is its own group).
        Map<String, MetricSample> latestPerInstance = new LinkedHashMap<>();
        for (MetricSample s : recent) {
            String key = s.getInstanceLabel() == null ? "__single__" : s.getInstanceLabel();
            latestPerInstance.putIfAbsent(key, s);
        }

        return latestPerInstance.values().stream().map(CurrentMetricDto::from).toList();
    }

    /**
     * Bounded history for one instance (or the sole instance, when
     * instanceLabel is null) of a metric type over a time window. Row
     * count is capped by nexus.history.max-points regardless of window
     * size, so a 30-day query can never return an unbounded result set.
     */
    public List<HistoryPointDto> history(MetricType type, String instanceLabel, Duration window) {
        String computerId = deviceIdentityService.getCurrent().getComputerId();
        Instant from = Instant.now().minus(window);
        Pageable bound = PageRequest.of(0, properties.getHistory().getMaxPoints(), Sort.by("timestamp").ascending());

        return metricSampleRepository.findHistory(computerId, type, instanceLabel, from, bound).stream()
                .map(HistoryPointDto::from)
                .toList();
    }

    /** Total rows available for a window, so callers can tell if the bounded query was truncated. */
    public long historyCount(MetricType type, String instanceLabel, Duration window) {
        String computerId = deviceIdentityService.getCurrent().getComputerId();
        Instant from = Instant.now().minus(window);
        return metricSampleRepository.countHistory(computerId, type, instanceLabel, from);
    }
}
