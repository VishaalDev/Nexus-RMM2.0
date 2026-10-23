package com.nexusrmm.dto;

import com.nexusrmm.entity.Computer;

import java.time.Instant;

public record DeviceDto(
        String computerId,
        String hostname,
        String manufacturer,
        String model,
        String osName,
        String osVersion,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
    public static DeviceDto from(Computer c) {
        return new DeviceDto(
                c.getComputerId(), c.getHostname(), c.getManufacturer(), c.getModel(),
                c.getOsName(), c.getOsVersion(), c.getFirstSeenAt(), c.getLastSeenAt()
        );
    }
}
