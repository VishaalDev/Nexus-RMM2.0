package com.nexusrmm.sync.config;

import com.nexusrmm.sync.SyncCoordinator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger. The tick only hands work to the sync thread and returns,
 * so it cannot hold up the shared scheduler that runs metric collection.
 * Same "${...}000" seconds idiom as the collector's interval.
 */
@Component
public class SyncScheduler {

    private final SyncCoordinator coordinator;

    public SyncScheduler(SyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${nexus.sync.poll-interval-seconds:10}000", initialDelayString = "15000")
    public void tick() {
        coordinator.tick();
    }
}
