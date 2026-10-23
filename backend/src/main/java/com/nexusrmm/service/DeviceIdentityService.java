package com.nexusrmm.service;

import com.nexusrmm.entity.Computer;
import com.nexusrmm.repository.ComputerRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.ComputerSystem;
import oshi.software.os.OperatingSystem;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owns the machine's identity record. On first startup this creates one
 * Computer row from real OSHI-reported system information and reuses it on
 * every subsequent boot. Multi-device fleet support (roadmap) would extend
 * this rather than replace it.
 */
@Service
public class DeviceIdentityService {

    private static final Logger log = LoggerFactory.getLogger(DeviceIdentityService.class);

    private final ComputerRepository computerRepository;
    private volatile Computer current;

    public DeviceIdentityService(ComputerRepository computerRepository) {
        this.computerRepository = computerRepository;
    }

    @PostConstruct
    public void initialize() {
        List<Computer> existing = computerRepository.findAll();
        if (!existing.isEmpty()) {
            current = existing.get(0);
            current.setLastSeenAt(Instant.now());
            computerRepository.save(current);
            log.info("Loaded existing computer identity: {} ({})", current.getComputerId(), current.getHostname());
            return;
        }

        SystemInfo systemInfo = new SystemInfo();
        OperatingSystem os = systemInfo.getOperatingSystem();
        ComputerSystem computerSystem = systemInfo.getHardware().getComputerSystem();

        String hostname = safe(os.getNetworkParams().getHostName(), "unknown-host");
        String manufacturer = safe(computerSystem.getManufacturer(), "Unknown");
        String model = safe(computerSystem.getModel(), "Unknown");
        String osName = safe(os.getFamily(), "Unknown");
        String osVersion = safe(os.getVersionInfo().getVersion(), "Unknown");

        Instant now = Instant.now();
        current = new Computer(
                UUID.randomUUID().toString(),
                hostname,
                manufacturer,
                model,
                osName,
                osVersion,
                now,
                now
        );
        computerRepository.save(current);
        log.info("Created new computer identity: {} ({})", current.getComputerId(), hostname);
    }

    public Computer getCurrent() {
        return current;
    }

    private static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
