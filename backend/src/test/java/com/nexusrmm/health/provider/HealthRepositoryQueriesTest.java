package com.nexusrmm.health.provider;

import com.nexusrmm.entity.HardwareEvent;
import com.nexusrmm.entity.MetricSample;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.provider.AvailabilityStatus;
import com.nexusrmm.repository.HardwareEventRepository;
import com.nexusrmm.repository.MetricSampleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend test area 20 (REQUIRES YOUR MAVEN BUILD - it needs Spring Boot,
 * Hibernate and the SQLite dialect, which could not be downloaded where
 * Module 2 was authored, so this class has NOT been executed by the author).
 *
 * It is the check on the one layer the plain-JDBC {@code HealthSqlTest}
 * cannot cover: that Spring Data wires the new queries correctly - JPQL
 * validated at startup, native-query parameter binding, interface
 * projections over SQLite's numeric types, and that the explicit @Convert on
 * HardwareEvent still stores epoch milliseconds. Setup deliberately mirrors
 * MetricSampleRepositoryTest (real Hibernate + hibernate-community-dialects +
 * a throw-away SQLite file).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class HealthRepositoryQueriesTest {

    private static final String COMPUTER = "c1";
    private static final long MIN = 60_000L;
    private static final long BUCKET = 30 * MIN;
    private static final long BASE = 1_790_000_000_000L - (1_790_000_000_000L % BUCKET);

    private static Path tempDb;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws IOException {
        tempDb = Files.createTempFile("nexus-rmm-health-repo-test-", ".db");
        Files.deleteIfExists(tempDb);
        tempDb.toFile().deleteOnExit();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDb);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    private MetricSampleRepository metrics;

    @Autowired
    private HardwareEventRepository events;

    @Autowired
    private EntityManager entityManager;

    private MetricSample sample(MetricType type, String instance, long ts, Double value, AvailabilityStatus status, String diag) {
        return new MetricSample(COMPUTER, type, instance, Instant.ofEpochMilli(ts), value, "%", status, "test", diag);
    }

    @Test
    void distinctInstanceLabelsExcludeNullOtherTypesAndOlderData() {
        metrics.save(sample(MetricType.STORAGE_UTILIZATION, "C:\\", BASE, 1.0, AvailabilityStatus.AVAILABLE, null));
        metrics.save(sample(MetricType.STORAGE_UTILIZATION, "D:\\", BASE, 2.0, AvailabilityStatus.AVAILABLE, null));
        metrics.save(sample(MetricType.STORAGE_UTILIZATION, null, BASE, 3.0, AvailabilityStatus.AVAILABLE, null));
        metrics.save(sample(MetricType.CPU_UTILIZATION, "X", BASE, 4.0, AvailabilityStatus.AVAILABLE, null));
        metrics.save(sample(MetricType.STORAGE_UTILIZATION, "OLD", BASE - 10 * BUCKET, 5.0, AvailabilityStatus.AVAILABLE, null));

        List<String> labels = metrics.findDistinctInstanceLabels(COMPUTER, MetricType.STORAGE_UTILIZATION, Instant.ofEpochMilli(BASE - BUCKET));

        assertEquals(2, labels.size());
        assertTrue(labels.containsAll(List.of("C:\\", "D:\\")));
    }

    @Test
    void latestReturnsTheNewestRowEvenWhenItHasNoValueAndMatchesNullInstancesOnlyForNull() {
        metrics.save(sample(MetricType.CPU_TEMPERATURE, null, BASE, 55.0, AvailabilityStatus.AVAILABLE, null));
        metrics.save(sample(MetricType.CPU_TEMPERATURE, null, BASE + MIN, null, AvailabilityStatus.UNSUPPORTED, "PawnIO driver not installed"));
        metrics.save(sample(MetricType.CPU_TEMPERATURE, "named", BASE + 2 * MIN, 99.0, AvailabilityStatus.AVAILABLE, null));

        var latestNull = metrics.findLatest(COMPUTER, MetricType.CPU_TEMPERATURE, null, PageRequest.of(0, 1));
        assertEquals(1, latestNull.size());
        assertEquals(AvailabilityStatus.UNSUPPORTED, latestNull.get(0).getAvailabilityStatus());
        assertEquals("PawnIO driver not installed", latestNull.get(0).getDiagnostic());

        var latestNamed = metrics.findLatest(COMPUTER, MetricType.CPU_TEMPERATURE, "named", PageRequest.of(0, 1));
        assertEquals(99.0, latestNamed.get(0).getValue());
    }

    @Test
    void nativeBucketQueriesBindParametersAndMapTheProjection() {
        MetricType t = MetricType.RAM_UTILIZATION;
        metrics.save(sample(t, "inst", BASE + MIN, 10.0, AvailabilityStatus.AVAILABLE, null));
        metrics.save(sample(t, "inst", BASE + 5 * MIN, 30.0, AvailabilityStatus.AVAILABLE, null));
        metrics.save(sample(t, "inst", BASE + 6 * MIN, null, AvailabilityStatus.UNAVAILABLE, "x"));
        metrics.save(sample(t, null, BASE + MIN, 77.0, AvailabilityStatus.AVAILABLE, null));
        metrics.flush();

        // metric_type is passed as the enum NAME: this asserts the stored representation matches HealthSql's assumption.
        var named = metrics.bucketsForInstance(COMPUTER, t.name(), "inst", BUCKET, BASE, BASE + BUCKET);
        assertEquals(1, named.size());
        assertEquals(BASE, named.get(0).getBucketStartMs().longValue());
        assertEquals(20.0, named.get(0).getAvgValue().doubleValue(), 1e-9);
        assertEquals(10.0, named.get(0).getMinValue().doubleValue(), 1e-9);
        assertEquals(30.0, named.get(0).getMaxValue().doubleValue(), 1e-9);
        assertEquals(3, named.get(0).getSampleCount().intValue());
        assertEquals(2, named.get(0).getValueCount().intValue());

        var single = metrics.bucketsForSingleInstance(COMPUTER, t.name(), BUCKET, BASE, BASE + BUCKET);
        assertEquals(1, single.size());
        assertEquals(77.0, single.get(0).getAvgValue().doubleValue(), 1e-9);
    }

    @Test
    void hardwareEventFindSinceIsNewestFirstBoundedAndStoresEpochMillis() {
        Instant now = Instant.ofEpochMilli(BASE);
        for (int i = 0; i < 5; i++) {
            events.save(new HardwareEvent(COMPUTER, 41, "Kernel-Power", 100L + i, "Critical", "POWER",
                    now.minusSeconds(3600L * i), now, "m" + i));
        }
        events.save(new HardwareEvent("other", 41, "Kernel-Power", 999L, "Critical", "POWER", now, now, "other computer"));
        events.flush();

        var recent = events.findSince(COMPUTER, now.minusSeconds(3600L * 3 + 1), PageRequest.of(0, 10));
        assertEquals(4, recent.size(), "events 0..3 hours old; the 4-hour-old one is before 'since'; the other computer is excluded");
        assertTrue(recent.get(0).getOccurredAt().isAfter(recent.get(1).getOccurredAt()), "newest first");
        assertEquals(2, events.findSince(COMPUTER, now.minusSeconds(86_400), PageRequest.of(0, 2)).size(), "bounded by the Pageable");

        // The explicit @Convert must still persist epoch milliseconds (not text), per the Part Z audit. Read through the
        // test's own EntityManager (same connection/transaction): a second JDBC connection would not see uncommitted rows.
        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT typeof(occurred_at), occurred_at FROM hardware_event WHERE record_id = 100")
                .getSingleResult();
        assertEquals("integer", row[0]);
        assertEquals(now.toEpochMilli(), ((Number) row[1]).longValue());
    }
}
