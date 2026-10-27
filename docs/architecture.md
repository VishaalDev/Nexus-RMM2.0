# Architecture — Module 1 Complete

This document covers the pipeline stages Module 1 owns. It will grow to
cover Module 2 (health/risk) and Module 3 (sync/alerts) as they land.

## Pipeline mapped to code

```
HARDWARE            OSHI: CentralProcessor, GlobalMemory, FileSystem,
                     HWDiskStore, NetworkIF, PowerSource, GraphicsCard,
                     Sensors, ComputerSystem — plus a fixed PowerShell
                     query against the Windows System event log
   ↓
COLLECTION           8 MetricProvider beans, auto-discovered by
                      MetricCollectorService; WindowsEventLogService
                      polls independently on its own schedule
   ↓
VALIDATION           MetricValidationService rejects implausible values
                      before they're persisted; a failing provider never
                      takes down the rest of the collection cycle
   ↓
LOCAL STORAGE        MetricSample rows (multi-instance aware) and
                      HardwareEvent rows, both in SQLite
   ↓
HISTORICAL DATA      MetricSampleRepository — bounded by max-points and
                      a 30-day window ceiling
   ↓
DASHBOARD / API       /api/metrics/*, /api/device/hardware, /api/events,
                      /api/diagnostics
```

Module 2 (health/trend/anomaly/risk) and Module 3 (issues/alerts/
recommendations/sync) are not implemented yet — they will read from the
same `MetricSample`/`HardwareEvent` history Module 1 now produces across
11 metric types.

## The extension point: `MetricProvider`

```java
public interface MetricProvider {
    Set<MetricType> providedMetricTypes();
    boolean supported();
    boolean available();
    String source();
    String diagnostic();
    List<MetricReading> collect();
}
```

Two changes since the vertical slice, both made to support real multi-
instance hardware (several disks, several NICs, several GPUs) without
forcing one class per metric:

1. `collect()` returns a **list** of readings — zero, one, or many, each
   independently tagged with its own `MetricType` and `instanceLabel`
   inside `MetricReading`.
2. `providedMetricTypes()` replaces the old single `metricType()`, so one
   provider can own a closely-related pair that comes from the same
   underlying poll (e.g. `StorageIoThroughputProvider` producing both read
   and write bytes/sec for each disk in one pass, rather than two
   providers double-polling the same device).

`MetricCollectorService` still just takes `List<MetricProvider> providers`
as a constructor argument — Spring auto-discovers every bean implementing
the interface. Nothing about the collector, schema, API, or (eventually)
frontend needs to change to add a ninth provider.

## Shared hardware access: `HardwareConfig`

Every provider now takes `HardwareAbstractionLayer`/`OperatingSystem` via
constructor injection from `HardwareConfig`, instead of each constructing
its own `SystemInfo()`. OSHI's `SystemInfo` does real platform setup on
construction (WMI/COM init on Windows); sharing one instance across 8
providers avoids doing that 8 times at startup.

## Multi-instance data model

`MetricSample.instanceLabel` (nullable) distinguishes concurrent instances
of the same metric type — a drive letter, a NIC's display name, a GPU's
name. Metrics that only ever have one instance on a machine (CPU
utilization, RAM utilization, CPU temperature) leave it null.
`MetricSampleRepository` and `MetricQueryService` are instance-aware in
both directions: "current value" groups the most recent sample per
instance within a short lookback window, and "history" takes an optional
instance filter.

## Capability model

`AvailabilityStatus` (`AVAILABLE`, `UNAVAILABLE`, `UNSUPPORTED`,
`PERMISSION_REQUIRED`, `NOT_APPLICABLE`, `ERROR`) is stored on every
sample. Every provider in this codebase demonstrates a different honest
case of it: `BatteryLevelProvider` reports `NOT_APPLICABLE` on desktops,
`GpuMetricsProvider` reports `UNSUPPORTED` because no vendor SDK is
integrated, `CpuTemperatureProvider` reports `UNAVAILABLE` with a specific
PawnIO-driver-presence diagnostic, and `MetricValidationService` converts
an implausible reading to `ERROR` rather than storing it. None of them
ever substitute a fabricated number for a real one.

## Database

SQLite via `org.xerial:sqlite-jdbc` + `hibernate-community-dialects`
(`SQLiteDialect`). Two tables now: `metric_sample` (time-series, indexed on
computer/type/instance/timestamp) and `hardware_event` (normalized System
log entries, unique-constrained on computer/provider/recordId for
idempotent re-polling). Still `ddl-auto: update` for this phase; a tracked
migration process replaces this before Module 3's larger schema lands.

## Known gaps / honesty notes

See `docs/hardware-monitoring.md` for the full, itemized list (GPU
counters, PawnIO MSR execution, SMART data, per-core temperature). The
short version: **this has not been compiled** — Maven Central is
unreachable from the sandbox that wrote it (confirmed via a live 403) —
though the previous milestone's `CpuUtilizationProvider`, which every new
provider follows the same pattern as, was confirmed built and running on
your real machine. `mvn clean package` on your machine is the next real
checkpoint.

## Module 3 — offline-first synchronization

Collection and analysis never depend on the network. Telemetry is stored locally
first; a separate `nexus-sync` thread copies it to an optional remote destination
through a durable SQLite queue (`sync_outbox`, additive migration v4) and marks
records synchronized **only** on an explicit acknowledgement from the destination.
With no destination configured the system reports `NOT_CONFIGURED` and keeps
everything local. Full design, payload/acknowledgement contract, retry and
idempotency rules and limitations: [`sync.md`](sync.md); API: [`api-sync.md`](api-sync.md);
schema: `database.md`.


## Module 4 — advisor

Reads Module 2's `HealthReport`/`RiskReport` (from one `HealthFacade.analysis()` snapshot,
so health and risk never come from different moments) and a cached in-memory process
snapshot, and produces ranked issues with recommendations and an alert log. Wired by
`AdvisorModuleConfig` exactly like `HealthModuleConfig`; no Module 1–3 bean is modified
(`HealthFacade` gained one additive method). Process data is never persisted or synced.
Full design, rules, privacy decisions and limitations: [`advisor.md`](advisor.md);
API: [`api-advisor.md`](api-advisor.md).
