package com.nexusrmm.sync.config;

import com.nexusrmm.service.DeviceIdentityService;
import com.nexusrmm.sync.HttpSyncClient;
import com.nexusrmm.sync.SyncClient;
import com.nexusrmm.sync.SyncCoordinator;
import com.nexusrmm.sync.SyncDevice;
import com.nexusrmm.sync.SyncEngine;
import com.nexusrmm.sync.SyncSettings;
import com.nexusrmm.sync.SyncStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Wires Module 3. The sync classes are plain Java assembled here, so Module
 * 1 and 2 beans (collector, repositories, health engines) are not modified
 * and do not depend on synchronization in any way.
 */
@Configuration
@EnableConfigurationProperties(SyncProperties.class)
public class SyncModuleConfig {

    @Bean
    public SyncSettings syncSettings(SyncProperties properties) {
        return properties.toSettings();
    }

    @Bean
    public SyncStore syncStore(DataSource dataSource) {
        return new SyncStore(dataSource, Clock.systemUTC());
    }

    @Bean
    public SyncClient syncClient(SyncSettings settings) {
        return new HttpSyncClient(settings);
    }

    @Bean
    public SyncEngine syncEngine(SyncStore store, SyncClient client, SyncSettings settings,
                                 DeviceIdentityService deviceIdentity, SyncProperties properties) {
        return new SyncEngine(store, client, settings, () -> {
            var computer = deviceIdentity.getCurrent();
            return new SyncDevice(computer.getComputerId(), computer.getHostname());
        }, Clock.systemUTC(), properties.getPollIntervalSeconds());
    }

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public SyncCoordinator syncCoordinator(SyncEngine engine, SyncStore store, SyncSettings settings) {
        return new SyncCoordinator(engine, store, settings);
    }
}
