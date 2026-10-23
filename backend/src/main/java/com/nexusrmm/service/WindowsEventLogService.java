package com.nexusrmm.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusrmm.config.NexusProperties;
import com.nexusrmm.entity.HardwareEvent;
import com.nexusrmm.repository.HardwareEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the Windows System event log for Critical/Error entries from a
 * curated, hardware-relevant provider list (WHEA, disk, NTFS, volume
 * snapshot, kernel power, PnP) using a single fixed PowerShell query — the
 * command text is hardcoded by this class, never built from user input, so
 * this is a read-only diagnostic query rather than arbitrary command
 * execution. New events are normalized and stored; the unique constraint
 * on (computerId, providerName, recordId) makes repeated polls of the same
 * lookback window idempotent. On any non-Windows platform, or if the
 * query fails, this reports its status via {@link #lastStatus()} rather
 * than silently doing nothing.
 */
@Service
public class WindowsEventLogService {

    private static final Logger log = LoggerFactory.getLogger(WindowsEventLogService.class);

    private static final List<String> HARDWARE_RELEVANT_PROVIDERS = List.of(
            "Microsoft-Windows-Kernel-WHEA",
            "disk",
            "Ntfs",
            "volsnap",
            "Microsoft-Windows-Kernel-Power",
            "Microsoft-Windows-Kernel-PnP"
    );

    public record Status(boolean applicable, boolean lastPollSuccess, Instant lastPollAt,
                          int lastNewEventCount, String lastError) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawEvent(
            @JsonProperty("Id") Integer id,
            @JsonProperty("ProviderName") String providerName,
            @JsonProperty("LevelDisplayName") String levelDisplayName,
            @JsonProperty("RecordId") Long recordId,
            @JsonProperty("TimeCreatedIso") String timeCreatedIso,
            @JsonProperty("Msg") String message
    ) {
    }

    private final HardwareEventRepository repository;
    private final DeviceIdentityService deviceIdentityService;
    private final NexusProperties properties;
    private final ObjectMapper objectMapper;

    private volatile Status status = new Status(false, false, null, 0, "Not polled yet.");

    public WindowsEventLogService(HardwareEventRepository repository,
                                   DeviceIdentityService deviceIdentityService,
                                   NexusProperties properties,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.deviceIdentityService = deviceIdentityService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Status lastStatus() {
        return status;
    }

    @Scheduled(fixedDelayString = "${nexus.events.poll-interval-seconds}000",
               initialDelayString = "5000")
    public void poll() {
        if (!properties.getEvents().isEnabled()) {
            status = new Status(false, false, Instant.now(), 0, "Event monitoring is disabled (nexus.events.enabled=false).");
            return;
        }
        if (!isWindows()) {
            status = new Status(false, false, Instant.now(), 0,
                    "Windows Event Log monitoring only applies on Windows; this host is running " + System.getProperty("os.name") + ".");
            return;
        }

        try {
            String json = runQuery(properties.getEvents().getLookbackHours());
            List<RawEvent> events = parse(json);
            int stored = persistNew(events);
            status = new Status(true, true, Instant.now(), stored, null);
        } catch (Exception e) {
            log.warn("Windows event log poll failed", e);
            status = new Status(true, false, Instant.now(), 0, e.getMessage());
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private String runQuery(int lookbackHours) throws Exception {
        String providerList = String.join(",", HARDWARE_RELEVANT_PROVIDERS.stream().map(p -> "'" + p + "'").toList());
        String script =
                "$ErrorActionPreference='SilentlyContinue';" +
                "$providers=@(" + providerList + ");" +
                "$events=@(Get-WinEvent -FilterHashtable @{LogName='System';Level=1,2;ProviderName=$providers;" +
                "StartTime=(Get-Date).AddHours(-" + lookbackHours + ")} -ErrorAction SilentlyContinue | " +
                "Select-Object Id,ProviderName,LevelDisplayName,RecordId," +
                "@{Name='TimeCreatedIso';Expression={$_.TimeCreated.ToUniversalTime().ToString('o')}}," +
                "@{Name='Msg';Expression={($_.Message -replace '\\s+',' ')}});" +
                "ConvertTo-Json -InputObject $events -Compress";

        Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(false)
                .start();

        String stdout = readAll(process.getInputStream());
        boolean finished = process.waitFor(15, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out querying the Windows System event log.");
        }
        return stdout;
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        in.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private List<RawEvent> parse(String json) throws Exception {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        // ConvertTo-Json on a single-element array (forced via @()) still
        // emits an array literal because of the @() wrapper in the script,
        // but guard defensively in case PowerShell unwraps it anyway.
        if (trimmed.startsWith("{")) {
            RawEvent single = objectMapper.readValue(trimmed, RawEvent.class);
            return List.of(single);
        }
        RawEvent[] array = objectMapper.readValue(trimmed, RawEvent[].class);
        return List.of(array);
    }

    private int persistNew(List<RawEvent> events) {
        String computerId = deviceIdentityService.getCurrent().getComputerId();
        int newCount = 0;

        for (RawEvent raw : events) {
            if (raw.recordId() == null || raw.providerName() == null) {
                continue;
            }
            if (repository.existsByComputerIdAndProviderNameAndRecordId(computerId, raw.providerName(), raw.recordId())) {
                continue;
            }

            Instant occurredAt = parseTimestamp(raw.timeCreatedIso());
            String message = raw.message() == null ? "" : raw.message();
            if (message.length() > 500) {
                message = message.substring(0, 500) + "…";
            }

            HardwareEvent event = new HardwareEvent(
                    computerId,
                    raw.id(),
                    raw.providerName(),
                    raw.recordId(),
                    raw.levelDisplayName(),
                    categorize(raw.providerName()),
                    occurredAt,
                    Instant.now(),
                    message
            );
            repository.save(event);
            newCount++;
        }
        return newCount;
    }

    private Instant parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private String categorize(String providerName) {
        String p = providerName.toLowerCase();
        if (p.contains("whea")) return "HARDWARE";
        if (p.contains("disk") || p.contains("ntfs") || p.contains("volsnap")) return "STORAGE";
        if (p.contains("power")) return "POWER";
        if (p.contains("pnp") || p.contains("driverframeworks")) return "DRIVER";
        return "SYSTEM";
    }
}
