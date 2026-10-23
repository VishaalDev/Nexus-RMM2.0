package com.nexusrmm.controller;

import com.nexusrmm.config.NexusProperties;
import com.nexusrmm.dto.DiagnosticsDto;
import com.nexusrmm.provider.MetricProvider;
import com.nexusrmm.repository.HardwareEventRepository;
import com.nexusrmm.repository.MetricSampleRepository;
import com.nexusrmm.service.DeviceIdentityService;
import com.nexusrmm.service.WindowsEventLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

@RestController
public class DiagnosticsController {

    private final List<MetricProvider> providers;
    private final MetricSampleRepository metricSampleRepository;
    private final HardwareEventRepository hardwareEventRepository;
    private final DeviceIdentityService deviceIdentityService;
    private final WindowsEventLogService windowsEventLogService;
    private final NexusProperties properties;

    public DiagnosticsController(List<MetricProvider> providers,
                                  MetricSampleRepository metricSampleRepository,
                                  HardwareEventRepository hardwareEventRepository,
                                  DeviceIdentityService deviceIdentityService,
                                  WindowsEventLogService windowsEventLogService,
                                  NexusProperties properties) {
        this.providers = providers;
        this.metricSampleRepository = metricSampleRepository;
        this.hardwareEventRepository = hardwareEventRepository;
        this.deviceIdentityService = deviceIdentityService;
        this.windowsEventLogService = windowsEventLogService;
        this.properties = properties;
    }

    @GetMapping("/api/diagnostics")
    public DiagnosticsDto diagnostics() {
        var computer = deviceIdentityService.getCurrent();

        List<DiagnosticsDto.ProviderStatus> providerStatuses = providers.stream()
                .map(p -> new DiagnosticsDto.ProviderStatus(
                        p.providedMetricTypes().stream().map(Enum::name).sorted().toList(),
                        p.supported(),
                        p.available(),
                        p.source(),
                        p.diagnostic()
                ))
                .sorted(Comparator.comparing(s -> s.metricTypes().toString()))
                .toList();

        var eventStatus = windowsEventLogService.lastStatus();

        return new DiagnosticsDto(
                "UP",
                computer.getComputerId(),
                computer.getHostname(),
                properties.getCollection().getIntervalSeconds(),
                properties.getDatabase().getPath(),
                metricSampleRepository.count(),
                hardwareEventRepository.countByComputerId(computer.getComputerId()),
                providerStatuses,
                new DiagnosticsDto.EventMonitoringStatus(
                        eventStatus.applicable(),
                        eventStatus.lastPollSuccess(),
                        eventStatus.lastPollAt(),
                        eventStatus.lastNewEventCount(),
                        eventStatus.lastError()
                )
        );
    }
}
