package com.nexusrmm.service;

import com.nexusrmm.entity.MetricSample;
import com.nexusrmm.provider.MetricProvider;
import com.nexusrmm.provider.MetricReading;
import com.nexusrmm.repository.MetricSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * The collection engine (Module 1, "COLLECT" + "VALIDATE" stages).
 *
 * Spring injects every bean implementing {@link MetricProvider} into the
 * list below automatically — adding a new sensor provider requires no
 * change here. Every poll writes one MetricSample per reading a provider
 * returns (one per disk/NIC/GPU/etc. for multi-instance providers),
 * whether or not the reading was available, so history never has a
 * silent, unexplained gap. Every AVAILABLE reading passes through
 * {@link MetricValidationService} before being persisted.
 *
 * Failure isolation is two layers deep: a provider that throws during
 * collect() never blocks any other provider, and — since the
 * SQLITE_CONSTRAINT_CHECK incident — a single reading that fails to
 * persist (e.g. a future schema mismatch for one metric type) never blocks
 * its sibling readings from the same provider either. Each save() is its
 * own try/catch rather than the whole provider's reading list sharing one.
 */
@Service
public class MetricCollectorService {

    private static final Logger log = LoggerFactory.getLogger(MetricCollectorService.class);

    private final List<MetricProvider> providers;
    private final MetricSampleRepository metricSampleRepository;
    private final DeviceIdentityService deviceIdentityService;
    private final MetricValidationService validationService;

    public MetricCollectorService(List<MetricProvider> providers,
                                   MetricSampleRepository metricSampleRepository,
                                   DeviceIdentityService deviceIdentityService,
                                   MetricValidationService validationService) {
        this.providers = providers;
        this.metricSampleRepository = metricSampleRepository;
        this.deviceIdentityService = deviceIdentityService;
        this.validationService = validationService;
        log.info("Metric collector initialized with {} provider(s) covering: {}",
                providers.size(),
                providers.stream().map(p -> p.providedMetricTypes().toString()).toList());
    }

    @Scheduled(fixedDelayString = "${nexus.collection.interval-seconds}000",
               initialDelayString = "1000")
    public void collectAll() {
        String computerId = deviceIdentityService.getCurrent().getComputerId();
        Instant timestamp = Instant.now();
        int written = 0;
        int failed = 0;

        for (MetricProvider provider : providers) {
            List<MetricReading> readings;
            try {
                readings = provider.collect();
            } catch (Exception e) {
                // A provider that throws during collection must never take
                // down the whole cycle for every other sensor.
                log.error("Unhandled failure collecting {}", provider.providedMetricTypes(), e);
                continue;
            }

            for (MetricReading raw : readings) {
                try {
                    MetricReading reading = validationService.validate(raw);
                    MetricSample sample = new MetricSample(
                            computerId,
                            reading.metricType(),
                            reading.instanceLabel(),
                            timestamp,
                            reading.value(),
                            reading.unit(),
                            reading.availabilityStatus(),
                            reading.source(),
                            reading.diagnostic()
                    );
                    metricSampleRepository.save(sample);
                    written++;
                } catch (Exception e) {
                    // A single reading failing to persist (e.g. a schema
                    // issue specific to one metric type) must never block
                    // its sibling readings from the same provider — this is
                    // exactly the isolation gap the SQLITE_CONSTRAINT_CHECK
                    // incident exposed.
                    failed++;
                    log.error("Failed to persist sample for {} [{}]: {}",
                            raw.metricType(), raw.instanceLabel(), e.getMessage());
                }
            }
        }

        if (failed > 0) {
            log.warn("Collection cycle wrote {} sample(s), {} failed to persist", written, failed);
        } else {
            log.debug("Collection cycle wrote {} sample(s)", written);
        }
    }
}
