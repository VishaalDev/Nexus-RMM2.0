package com.nexusrmm.dto;

import java.time.Instant;
import java.util.List;

public record HardwareSnapshotDto(
        SystemIdentity system,
        Cpu cpu,
        Memory memory,
        List<StorageDevice> storageDevices,
        List<Volume> volumes,
        List<Gpu> gpus,
        Battery battery,
        List<NetworkAdapter> networkAdapters
) {
    public record SystemIdentity(
            String hostname,
            String manufacturer,
            String model,
            String biosVendor,
            String biosVersion,
            String biosReleaseDate,
            String baseboardManufacturer,
            String baseboardModel,
            String osFamily,
            String osVersion,
            String osBuild,
            String architecture,
            Instant bootTime
    ) {
    }

    public record Cpu(
            String name,
            String vendor,
            String identifier,
            int physicalCores,
            int logicalCores,
            long maxFreqHz
    ) {
    }

    public record Memory(
            long totalBytes,
            List<MemoryModule> modules
    ) {
    }

    public record MemoryModule(
            String bankLabel,
            long capacityBytes,
            long speedMHz,
            String manufacturer,
            String memoryType
    ) {
    }

    public record StorageDevice(
            String name,
            String model,
            long sizeBytes,
            boolean smartAvailable
    ) {
    }

    public record Volume(
            String mount,
            String name,
            String fileSystemType,
            long totalBytes,
            long usableBytes
    ) {
    }

    public record Gpu(
            String name,
            String vendor,
            long vramBytes
    ) {
    }

    public record Battery(
            boolean present,
            List<PowerSourceInfo> sources
    ) {
    }

    public record PowerSourceInfo(
            String name,
            double remainingCapacityPercent,
            int designCapacity,
            int maxCapacity,
            String capacityUnit,
            boolean charging,
            boolean powerOnLine
    ) {
    }

    public record NetworkAdapter(
            String name,
            String displayName,
            String macAddress,
            long speedBps,
            String operStatus
    ) {
    }
}
