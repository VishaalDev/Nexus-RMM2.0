package com.nexusrmm.dto;

import java.time.Instant;
import java.util.List;

public record DiagnosticsDto(
        String applicationStatus,
        String computerId,
        String hostname,
        int collectionIntervalSeconds,
        String databasePath,
        long totalStoredSamples,
        long totalStoredEvents,
        List<ProviderStatus> providers,
        EventMonitoringStatus eventMonitoring
) {
    public record ProviderStatus(
            List<String> metricTypes,
            boolean supported,
            boolean available,
            String source,
            String diagnostic
    ) {
    }

    public record EventMonitoringStatus(
            boolean applicable,
            boolean lastPollSuccess,
            Instant lastPollAt,
            int lastNewEventCount,
            String lastError
    ) {
    }
}
