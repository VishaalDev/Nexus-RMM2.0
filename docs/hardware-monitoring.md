# Module 1 — Hardware Monitoring & Hardware DNA

## Status

All planned providers for this phase are implemented and reviewed. **Not
yet verified against real hardware** — see "How to verify" below; that
step happens on your machine, not in the sandbox this was written in.

## Providers (live, time-series metrics)

| Provider | Metric type(s) | Instance model | Source |
|---|---|---|---|
| `CpuUtilizationProvider` | `CPU_UTILIZATION` | single | OSHI `CentralProcessor` tick-delta |
| `RamUtilizationProvider` | `RAM_UTILIZATION` | single | OSHI `GlobalMemory` |
| `StorageUtilizationProvider` | `STORAGE_UTILIZATION` | one per mounted volume | OSHI `FileSystem` |
| `StorageIoThroughputProvider` | `STORAGE_READ_BYTES_PER_SEC`, `STORAGE_WRITE_BYTES_PER_SEC` | one per physical disk | OSHI `HWDiskStore` counter deltas |
| `NetworkThroughputProvider` | `NETWORK_RX_BYTES_PER_SEC`, `NETWORK_TX_BYTES_PER_SEC` | one per adapter that is operationally up | OSHI `NetworkIF` counter deltas |
| `BatteryLevelProvider` | `BATTERY_LEVEL` | one per power source (or `NOT_APPLICABLE` on desktops) | OSHI `PowerSource` |
| `GpuMetricsProvider` | `GPU_UTILIZATION`, `GPU_TEMPERATURE` | one per detected GPU | OSHI `GraphicsCard` (enumeration only — see limitation below) |
| `CpuTemperatureProvider` | `CPU_TEMPERATURE` | single | OSHI `Sensors`, with PawnIO driver-presence diagnostic |

All of them implement the same `MetricProvider` contract described in
`architecture.md`. Every reading a provider returns is either a real value
or an honestly-tagged `AvailabilityStatus` explaining why it isn't — no
provider in this codebase ever fabricates a number.

## Hardware DNA (static specification snapshot)

`GET /api/device/hardware` — built by `HardwareSnapshotService`, fresh on
each request, from OSHI's `ComputerSystem`, `CentralProcessor`,
`GlobalMemory`/`PhysicalMemory`, `HWDiskStore`, `OSFileStore`,
`GraphicsCard`, `PowerSource` and `NetworkIF` APIs. This is static identity
and specification data (model numbers, capacities, firmware versions) —
distinct from the live time-series `/api/metrics/*` endpoints.

## Hardware/system event monitoring

`WindowsEventLogService` polls the Windows **System** event log every
`nexus.events.poll-interval-seconds` (default 60s) for Critical/Error
entries from a curated, hardware-relevant provider list:
`Microsoft-Windows-Kernel-WHEA`, `disk`, `Ntfs`, `volsnap`,
`Microsoft-Windows-Kernel-Power`, `Microsoft-Windows-Kernel-PnP`.

The query is a single **fixed** PowerShell command (`Get-WinEvent` with a
hardcoded filter) — never built from user input — so this is a read-only
diagnostic query, not arbitrary command execution, consistent with the
project's safe-management-only rule. Events are normalized (truncated
message, mapped category, deduplicated by
`(computerId, providerName, recordId)`) into `HardwareEvent` rows; the raw
log is never dumped wholesale. On any non-Windows host, or if the query
fails, this is reported honestly via `/api/diagnostics`, not silently
ignored.

## Data validation

`MetricValidationService` runs on every `AVAILABLE` reading before it is
persisted. Values outside a metric's plausible physical range (e.g. a
percentage outside 0–100, a byte rate that implies a 64-bit counter
wraparound) are converted to an `ERROR` sample with a diagnostic explaining
why, rather than stored as if they were real. This is separate from, and
runs before, the trend/anomaly analysis Module 2 will add.

## Bounded queries

- `/api/metrics/history` accepts `window` up to 30 days and caps the
  returned row count at `nexus.history.max-points` (default 5000)
  regardless of window size — a 30-day query can never return an unbounded
  result set.
- `/api/metrics/current` looks back only a few collection intervals
  (`nexus.history.current-value-lookback-multiplier`) so a stale sample is
  never reported as "current."
- `/api/events` is capped at a caller-supplied `limit`, hard-ceilinged at
  500.

## Diagnostics

`GET /api/diagnostics` exposes, in one place: every registered provider's
`supported`/`available`/`source`/`diagnostic` state, total stored samples
and events, the event-monitoring subsystem's last poll result, and the
active collection interval and database path. This is the live capability
matrix the whole project's "never fake a missing sensor" rule depends on
being checkable.

## Known limitations (honest, as of this phase)

- **Not yet compiled or run.** This sandbox cannot reach Maven Central
  (confirmed via a live `mvn dependency:resolve` 403), so none of this
  code has been built here. It follows the same structural pattern as
  `CpuUtilizationProvider`, which **was** confirmed built and running on
  your machine in the previous milestone — but that is not a substitute
  for actually compiling the new files. Run `mvn clean package` and report
  back what it says.
- **GPU utilization/temperature are genuinely unsupported in this build.**
  OSHI does not expose real-time GPU counters; that requires a vendor SDK
  (NVIDIA NVML, AMD ADL) not yet integrated. GPU inventory (name, vendor,
  VRAM) is available via the Hardware DNA endpoint; live utilization/
  temperature correctly report `UNSUPPORTED` per GPU rather than a fake
  number.
- **CPU temperature commonly reports `UNAVAILABLE` on Windows** unless a
  compatible sensor driver is present, because Windows does not expose a
  standard thermal sensor API. `PawnIoDriverDetector` does real,
  non-fabricated detection of whether the PawnIO kernel driver is
  installed (`sc query PawnIO`), and the diagnostic message reflects that
  — but this build does **not** implement the PawnIO MSR execution
  protocol (loading a compiled module such as `IntelMSR.bin` and reading
  registers via `DeviceIoControl`). That protocol was identified during
  development (PawnIO device type `41394`, `IOCTL_PIO_LOAD_BINARY` /
  `IOCTL_PIO_EXECUTE_FN`) but implementing the register-level read without
  real hardware to validate the result against was judged too likely to
  produce a *wrong* number silently, which this project's rules treat as
  worse than reporting "unsupported." A future implementation needs: the
  PawnIO driver installed, a compiled `IntelMSR.bin` module (ships with
  LibreHardwareMonitor's PawnIO Modules, under its own license), and JNA
  or JNI bindings to `CreateFile`/`DeviceIoControl` on the PawnIO device.
- **SMART/NVMe health attributes are not implemented.** OSHI's core API
  does not expose them; `HardwareSnapshotDto.StorageDevice.smartAvailable`
  is honestly hardcoded `false` rather than guessed. A future
  implementation would need a platform SMART library.
- **Per-core CPU temperature/utilization is not implemented** — only
  system-wide CPU utilization and overall package temperature. Per-core
  breakdown is a reasonable future extension of `CpuUtilizationProvider`.
- **Windows Event Log monitoring only runs on Windows**, by design — it is
  reported as `applicable: false` (not an error) on any other platform via
  `/api/diagnostics`.

## How to verify (on your Windows machine)

1. `cd backend && mvn clean package` — first real compiler check.
2. `mvn spring-boot:run`, then:
   - `GET /api/metrics/current` — after ~20s, should show real CPU, RAM,
     per-volume storage, per-disk I/O, and (if applicable) battery and
     network readings; GPU entries should show `UNSUPPORTED`.
   - `GET /api/device/hardware` — should show your actual CPU model, RAM
     modules, disks, GPU(s), and adapters.
   - `GET /api/diagnostics` — check every provider's `available`/
     `diagnostic` matches what you'd expect for your machine (e.g. a
     desktop should show `BATTERY_LEVEL` as `NOT_APPLICABLE`, a laptop
     should show it `AVAILABLE`).
   - `GET /api/events` — after a couple of minutes, should be an empty
     list on a healthy machine (that's correct — it only reports
     Critical/Error entries, not routine activity) with
     `eventMonitoring.applicable: true` in `/api/diagnostics`.
3. Report back anything that doesn't compile, doesn't match your actual
   hardware, or throws at runtime — that feedback is what turns this from
   "written" into "verified," per the project's own build-and-test rule.
