package com.nexusrmm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OperatingSystem;

/**
 * OSHI's SystemInfo does real platform-level setup on construction
 * (WMI/COM initialization on Windows, /proc parsing on Linux, etc.). Every
 * provider shares the single instance below via constructor injection
 * instead of creating its own — cheaper at startup and keeps device
 * identity, CPU, memory, disk, network, sensor and power-source access all
 * reading from the same underlying platform handle.
 */
@Configuration
public class HardwareConfig {

    @Bean
    public SystemInfo systemInfo() {
        return new SystemInfo();
    }

    @Bean
    public HardwareAbstractionLayer hardwareAbstractionLayer(SystemInfo systemInfo) {
        return systemInfo.getHardware();
    }

    @Bean
    public OperatingSystem operatingSystem(SystemInfo systemInfo) {
        return systemInfo.getOperatingSystem();
    }
}
