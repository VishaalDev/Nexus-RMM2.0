package com.nexusrmm.sync;

import com.nexusrmm.entity.Computer;
import com.nexusrmm.entity.MetricSample;
import com.nexusrmm.entity.MetricType;
import com.nexusrmm.provider.AvailabilityStatus;
import com.nexusrmm.provider.MetricProvider;
import com.nexusrmm.provider.MetricReading;
import com.nexusrmm.repository.ComputerRepository;
import com.nexusrmm.repository.MetricSampleRepository;
import com.nexusrmm.service.DeviceIdentityService;
import com.nexusrmm.service.MetricCollectorService;
import com.nexusrmm.service.MetricValidationService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.nexusrmm.sync.SyncTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Module 1 integration: the REAL {@link MetricCollectorService} keeps writing
 * telemetry while the destination is down, and every row it wrote is later
 * delivered once the destination returns. The repository is a thin JDK proxy
 * that writes the entity's real getter values into the same SQLite schema,
 * so no Spring context or Hibernate is needed.
 */
class CollectionDuringOutageTest {

    private static MetricSampleRepository jdbcBackedRepository(DataSource ds) {
        return (MetricSampleRepository) Proxy.newProxyInstance(
                MetricSampleRepository.class.getClassLoader(), new Class<?>[]{MetricSampleRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("save")) {
                        MetricSample s = (MetricSample) args[0];
                        try (Connection c = ds.getConnection();
                             PreparedStatement ps = c.prepareStatement("INSERT INTO metric_sample (availability_status, computer_id, "
                                     + "diagnostic, instance_label, metric_type, source, timestamp, unit, value) VALUES (?,?,?,?,?,?,?,?,?)")) {
                            ps.setString(1, s.getAvailabilityStatus().name());
                            ps.setString(2, s.getComputerId());
                            ps.setString(3, s.getDiagnostic());
                            ps.setString(4, s.getInstanceLabel());
                            ps.setString(5, s.getMetricType().name());
                            ps.setString(6, s.getSource());
                            ps.setLong(7, s.getTimestamp().toEpochMilli());
                            ps.setString(8, s.getUnit());
                            if (s.getValue() == null) {
                                ps.setNull(9, Types.DOUBLE);
                            } else {
                                ps.setDouble(9, s.getValue());
                            }
                            ps.executeUpdate();
                        }
                        return s;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static DeviceIdentityService identity() {
        Computer computer = new Computer("pc-1", "TEST-HOST", "m", "model", "os", "1", Instant.now(), Instant.now());
        ComputerRepository repo = (ComputerRepository) Proxy.newProxyInstance(
                ComputerRepository.class.getClassLoader(), new Class<?>[]{ComputerRepository.class},
                (proxy, method, args) -> method.getName().equals("findAll") ? List.of(computer) : args == null ? null : args[0]);
        DeviceIdentityService service = new DeviceIdentityService(repo);
        service.initialize(); // existing identity path: no OSHI access
        return service;
    }

    private static MetricProvider provider() {
        return new MetricProvider() {
            @Override public Set<MetricType> providedMetricTypes() { return Set.of(MetricType.CPU_UTILIZATION, MetricType.CPU_TEMPERATURE); }
            @Override public boolean supported() { return true; }
            @Override public boolean available() { return true; }
            @Override public String source() { return "test"; }
            @Override public String diagnostic() { return "test"; }
            @Override public List<MetricReading> collect() {
                return List.of(
                        MetricReading.available(MetricType.CPU_UTILIZATION, null, 0.0, "%", "test"),
                        MetricReading.unavailable(MetricType.CPU_TEMPERATURE, null, "test", "no sensor"));
            }
        };
    }

    @Test
    void collectionKeepsRunningDuringAnOutageAndTheBacklogIsDeliveredAfterwards() throws Exception {
        DataSource ds = dataSource(newDbPath());
        createAll(ds);
        MetricCollectorService collector = new MetricCollectorService(List.of(provider()), jdbcBackedRepository(ds),
                identity(), new MetricValidationService());

        String deadUrl = "http://127.0.0.1:" + freePortNotListening() + "/ingest";
        SyncSettings dead = settings(deadUrl, 100);
        SyncStore store = new SyncStore(ds, Clock.systemUTC());
        store.initializeCursors(true);
        SyncEngine outageEngine = new SyncEngine(store, new HttpSyncClient(dead), dead, () -> new SyncDevice("pc-1", "TEST-HOST"), Clock.systemUTC(), 10);

        collector.collectAll();
        assertEquals(SyncCycleResult.Outcome.FAILED, outageEngine.runCycle(true).outcome());
        collector.collectAll();
        collector.collectAll();
        outageEngine.runCycle(true);

        assertEquals(6, scalar(ds, "SELECT COUNT(*) FROM metric_sample"), "3 cycles x 2 readings were stored locally");
        assertEquals(6, outageEngine.status().queue().pending());
        assertEquals(0, outageEngine.status().queue().synced());

        try (FakeDestination d = new FakeDestination()) {
            SyncSettings live = settings(d.url(), 100);
            SyncEngine liveEngine = new SyncEngine(store, new HttpSyncClient(live), live, () -> new SyncDevice("pc-1", "TEST-HOST"), Clock.systemUTC(), 10);
            assertEquals(SyncCycleResult.Outcome.SUCCESS, liveEngine.runCycle(true).outcome());
            assertEquals(6, d.stored.size());
            long zeros = d.requestBodies.get(0).get("records").findValues("value").stream()
                    .filter(v -> v.isNumber() && v.asDouble() == 0.0).count();
            long nulls = d.requestBodies.get(0).get("records").findValues("value").stream().filter(v -> v.isNull()).count();
            assertTrue(zeros >= 1 && nulls >= 1, "real zero and unavailable stay distinct end to end");
            assertEquals(6, liveEngine.status().queue().synced());
        }
    }
}
