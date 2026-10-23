package com.nexusrmm.service;

import com.nexusrmm.dto.HardwareSnapshotDto;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.ComputerSystem;
import oshi.hardware.GlobalMemory;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.hardware.PhysicalMemory;
import oshi.hardware.PowerSource;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.time.Instant;
import java.util.List;

/**
 * Builds the "Hardware DNA" — the machine's static identity and
 * specifications — as opposed to {@link MetricQueryService}, which serves
 * the live/historical time series. Built fresh from OSHI on each request
 * rather than cached, since this is called rarely (a Hardware page load),
 * not on every 10-second poll.
 */
@Service
public class HardwareSnapshotService {

    private final HardwareAbstractionLayer hal;
    private final OperatingSystem os;

    public HardwareSnapshotService(SystemInfo systemInfo, HardwareAbstractionLayer hal, OperatingSystem os) {
        this.hal = hal;
        this.os = os;
    }

    public HardwareSnapshotDto snapshot() {
        return new HardwareSnapshotDto(
                buildSystemIdentity(),
                buildCpu(),
                buildMemory(),
                buildStorageDevices(),
                buildVolumes(),
                buildGpus(),
                buildBattery(),
                buildNetworkAdapters()
        );
    }

    private HardwareSnapshotDto.SystemIdentity buildSystemIdentity() {
        ComputerSystem cs = hal.getComputerSystem();
        long bootEpochSeconds = os.getSystemBootTime();

        return new HardwareSnapshotDto.SystemIdentity(
                safe(os.getNetworkParams().getHostName()),
                safe(cs.getManufacturer()),
                safe(cs.getModel()),
                safe(cs.getFirmware().getManufacturer()),
                safe(cs.getFirmware().getVersion()),
                safe(cs.getFirmware().getReleaseDate()),
                safe(cs.getBaseboard().getManufacturer()),
                safe(cs.getBaseboard().getModel()),
                safe(os.getFamily()),
                safe(os.getVersionInfo().getVersion()),
                safe(os.getVersionInfo().getBuildNumber()),
                System.getProperty("os.arch", "unknown"),
                bootEpochSeconds > 0 ? Instant.ofEpochSecond(bootEpochSeconds) : null
        );
    }

    private HardwareSnapshotDto.Cpu buildCpu() {
        CentralProcessor cpu = hal.getProcessor();
        var id = cpu.getProcessorIdentifier();

        return new HardwareSnapshotDto.Cpu(
                safe(id.getName()),
                safe(id.getVendor()),
                safe(id.getIdentifier()),
                cpu.getPhysicalProcessorCount(),
                cpu.getLogicalProcessorCount(),
                cpu.getMaxFreq()
        );
    }

    private HardwareSnapshotDto.Memory buildMemory() {
        GlobalMemory memory = hal.getMemory();
        List<HardwareSnapshotDto.MemoryModule> modules = memory.getPhysicalMemory().stream()
                .map(this::toMemoryModule)
                .toList();

        return new HardwareSnapshotDto.Memory(memory.getTotal(), modules);
    }

    private HardwareSnapshotDto.MemoryModule toMemoryModule(PhysicalMemory m) {
        return new HardwareSnapshotDto.MemoryModule(
                safe(m.getBankLabel()),
                m.getCapacity(),
                m.getClockSpeed() / 1_000_000L, // Hz -> MHz
                safe(m.getManufacturer()),
                safe(m.getMemoryType())
        );
    }

    private List<HardwareSnapshotDto.StorageDevice> buildStorageDevices() {
        List<HWDiskStore> disks = hal.getDiskStores();
        return disks.stream()
                .map(d -> new HardwareSnapshotDto.StorageDevice(
                        safe(d.getName()),
                        safe(d.getModel()),
                        d.getSize(),
                        // OSHI's core API does not expose SMART attributes; a real
                        // reading would require a platform-specific SMART library
                        // (e.g. smartmontools bindings). Honestly false, not guessed.
                        false
                ))
                .toList();
    }

    private List<HardwareSnapshotDto.Volume> buildVolumes() {
        List<OSFileStore> stores = os.getFileSystem().getFileStores(true);
        return stores.stream()
                .filter(s -> s.getTotalSpace() > 0)
                .map(s -> new HardwareSnapshotDto.Volume(
                        safe(s.getMount()),
                        safe(s.getName()),
                        safe(s.getType()),
                        s.getTotalSpace(),
                        s.getUsableSpace()
                ))
                .toList();
    }

    private List<HardwareSnapshotDto.Gpu> buildGpus() {
        List<GraphicsCard> cards = hal.getGraphicsCards();
        return cards.stream()
                .map(g -> new HardwareSnapshotDto.Gpu(safe(g.getName()), safe(g.getVendor()), g.getVRam()))
                .toList();
    }

    private HardwareSnapshotDto.Battery buildBattery() {
        List<PowerSource> sources = hal.getPowerSources();
        List<HardwareSnapshotDto.PowerSourceInfo> infos = sources.stream()
                .map(ps -> new HardwareSnapshotDto.PowerSourceInfo(
                        safe(ps.getName()),
                        ps.getRemainingCapacityPercent() * 100.0,
                        ps.getDesignCapacity(),
                        ps.getMaxCapacity(),
                        ps.getCapacityUnits() != null ? ps.getCapacityUnits().toString() : "UNKNOWN",
                        ps.isCharging(),
                        ps.isPowerOnLine()
                ))
                .toList();

        return new HardwareSnapshotDto.Battery(!infos.isEmpty(), infos);
    }

    private List<HardwareSnapshotDto.NetworkAdapter> buildNetworkAdapters() {
        List<NetworkIF> interfaces = hal.getNetworkIFs();
        return interfaces.stream()
                .map(nif -> new HardwareSnapshotDto.NetworkAdapter(
                        safe(nif.getName()),
                        safe(nif.getDisplayName()),
                        safe(nif.getMacaddr()),
                        nif.getSpeed(),
                        nif.getIfOperStatus() != null ? nif.getIfOperStatus().toString() : "UNKNOWN"
                ))
                .toList();
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "Unknown" : s;
    }
}
