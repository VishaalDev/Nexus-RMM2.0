package com.nexusrmm.advisor.service;

import com.nexusrmm.advisor.config.AdvisorProperties;
import com.nexusrmm.advisor.config.AdvisorSettings;
import com.nexusrmm.advisor.process.ProcessAggregator;
import com.nexusrmm.advisor.process.ProcessSnapshot;
import com.nexusrmm.advisor.process.ProcessSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Samples the process table on its own schedule and keeps only the latest
 * aggregated snapshot (nothing is persisted: process lists are not
 * telemetry worth storing, and storing them would put program names on
 * disk and into the sync queue). Requests are answered from the cache, so
 * the API never does platform work on the request thread. A failing sample
 * becomes an explicit "unavailable" snapshot, and a snapshot that has gone
 * stale is never presented as current.
 */
@Service
public class ProcessSamplerService {

    private static final Logger log = LoggerFactory.getLogger(ProcessSamplerService.class);

    private final ProcessSource source;
    private final AdvisorSettings settings;
    private final AdvisorProperties properties;
    private volatile ProcessSnapshot latest;
    private Supplier<Instant> clock = Instant::now;

    public ProcessSamplerService(ProcessSource source, AdvisorSettings settings, AdvisorProperties properties) {
        this.source = source;
        this.settings = settings;
        this.properties = properties;
    }

    /** Test hook: replaces the time source. */
    void useClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nexus.advisor.process-interval-seconds:30}000", initialDelayString = "5000")
    public void sample() {
        if (!properties.isProcessSamplingEnabled()) {
            return;
        }
        Instant now = clock.get();
        try {
            latest = ProcessAggregator.aggregate(source.capture(), settings.processTopN(), now);
        } catch (RuntimeException e) {
            log.warn("Process sampling failed: {}", e.toString());
            latest = ProcessSnapshot.unavailable(now, "Process sampling failed (" + e.getClass().getSimpleName() + "); see the backend log.");
        }
    }

    /** Never null: always a snapshot, possibly an explicit "unavailable" one. */
    public ProcessSnapshot latest() {
        Instant now = clock.get();
        if (!properties.isProcessSamplingEnabled()) {
            return ProcessSnapshot.unavailable(now, "Process sampling is disabled (nexus.advisor.process-sampling-enabled=false).");
        }
        ProcessSnapshot s = latest;
        if (s == null) {
            return ProcessSnapshot.unavailable(now, "No process sample has been taken yet; the first one arrives a few seconds after startup.");
        }
        Duration age = Duration.between(s.capturedAt(), now);
        if (s.available() && age.compareTo(Duration.ofSeconds(properties.getProcessIntervalSeconds() * 4L)) > 0) {
            return ProcessSnapshot.unavailable(now, "The last process sample is " + age.toSeconds() + " s old, so it is not shown as current.");
        }
        return s;
    }
}
