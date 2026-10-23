# Nexus-RMM

**A Lightweight Monitoring System with Offline Data Synchronization**

> Status: **Modules 1 (hardware monitoring), 2 (health analysis & failure
> risk) and 3 (offline-first synchronization) are implemented.** Issue
> engine / alerts / recommendations remain future work. Module 3 adds a
> durable sync queue, a configurable sync client and a Sync page; see
> [`docs/sync.md`](docs/sync.md).

## What's real right now

**8 hardware providers**, each collecting actual data from the host
machine via OSHI (plus one Windows-only event-log poller), all following
one rule with no exceptions: report a real value, or report *why* there
isn't one — never a fabricated number.

| Metric | Instance model | Notes |
|---|---|---|
| CPU utilization | single | tick-delta, honest baseline on first cycle |
| RAM utilization | single | immediate, no warm-up needed |
| Storage utilization | per volume | skips pseudo filesystems with no real capacity |
| Storage read/write throughput | per physical disk | counter-delta, same pattern as CPU |
| Network rx/tx throughput | per adapter that's operationally up | down/disconnected adapters aren't reported as 0 |
| Battery level | per power source | correctly `NOT_APPLICABLE` on desktops |
| GPU utilization/temperature | per GPU | correctly `UNSUPPORTED` — OSHI has no vendor-SDK access, so this is reported honestly rather than faked |
| CPU temperature | single | OSHI sensor path; when unavailable, folds in a **real** PawnIO driver-presence check (`sc query PawnIO`), not a guess |

Also implemented: a static **Hardware DNA** snapshot (system identity, CPU,
memory modules, storage devices, GPUs, network adapters), **Windows
hardware-event monitoring** (a fixed, non-user-controlled PowerShell query
against the System log for WHEA/disk/power/PnP Critical-Error entries,
deduplicated), **data validation** (implausible readings are converted to
an `ERROR` sample before storage, never persisted as real), and **bounded
history queries** (30-day window ceiling, 5000-point cap regardless of
window size).

## What's not built yet

- Issue engine, alerts and recommendations
- A built-in remote destination: Module 3 synchronizes to a receiver *you*
  provide (contract in `docs/sync.md`); none is bundled or faked
- Frontend pages for Issues, Alerts, Reports and Settings
- SMART/NVMe storage health, GPU real-time counters (vendor SDK), PawnIO
  MSR register execution, per-core CPU temperature — see
  `docs/hardware-monitoring.md` for exactly why each of these is out of
  scope for now rather than faked.

## Fixed: SQLITE_CONSTRAINT_CHECK on new metric types

The 4 metric types added for storage/network throughput
(`NETWORK_TX_BYTES_PER_SEC`, `NETWORK_RX_BYTES_PER_SEC`,
`STORAGE_READ_BYTES_PER_SEC`, `STORAGE_WRITE_BYTES_PER_SEC`) were being
rejected by a stale Hibernate-generated CHECK constraint left over from
before the enum grew. An explicit `columnDefinition` was tried first and,
on inspection of the actual generated schema, did **not** suppress the
constraint. Fixed permanently instead by mapping both enum columns through
a plain JPA `AttributeConverter` — which stops Hibernate from ever
resolving them as enum-typed columns, so the constraint-generation code
path never runs at all — and repaired safely on any existing database (a
versioned migration rebuilds the table without losing data). Verified
against the actual generated DDL, not just the annotation. Full
explanation: `docs/database.md`.

## Project layout

```
Nexus-RMM-Final/
├── backend/     Spring Boot (Java 17), SQLite, 8 OSHI-based providers + event monitor, sync engine
├── tools/       sync-test-receiver.mjs — optional dev-only receiver for trying sync locally
├── frontend/    React + Vite, Recharts (Overview page only so far)
└── docs/
    ├── architecture.md          system-wide design, updated each phase
    └── hardware-monitoring.md   Module 1 detail + itemized known limitations
```

## Running it

### Backend

Requires JDK 17+ and Maven, with normal internet access to Maven Central
(this was written in a sandbox that cannot reach Maven Central — it has
**not been compiled** here; see `docs/hardware-monitoring.md`).

```bash
cd backend
mvn clean package
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. On first run it creates
`backend/data/nexus-rmm.db` and a `Computer` identity row from your real
system information.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Still just the Overview page (CPU only) from
the vertical slice — it hasn't been updated to show the new Module 1
metrics yet. `npm run build`, `npm run lint`, and `npm run test` all
actually passed in this environment.

## API (current)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | Backend liveness |
| GET | `/api/device` | Computer identity (hostname, model, OS…) |
| GET | `/api/device/hardware` | Hardware DNA — static specs (CPU, memory modules, disks, GPUs, adapters) |
| GET | `/api/metrics/current` | Latest value for every (metric, instance) with fresh data |
| GET | `/api/metrics/history` | `?metric=X&instance=Y&window=1h/6h/24h/7d/30d` (instance optional, window capped at 30d) |
| GET | `/api/events` | Recent normalized hardware/system events (`?limit=`, capped at 500) |
| GET | `/api/diagnostics` | Provider capability matrix, storage counts, event-monitor status |
| GET | `/api/sync/status` | Module 3: configuration, connectivity, queue counts, last attempt/success, errors |
| POST | `/api/sync/trigger` | Module 3: "Sync Now" — starts a background run (202), or 409 if running / not configured |

Module 2 endpoints (`/api/health/*`, `/api/risk*`) are in `docs/api-health.md`.

## Offline synchronization (Module 3)

Telemetry is always stored locally first. With **no destination configured the
Sync page and API report `NOT_CONFIGURED`** and nothing is sent; local monitoring
is unaffected. To enable synchronization point the backend at a receiver that
implements the contract in `docs/sync.md`:

```powershell
$env:NEXUS_SYNC_DESTINATION_URL = "https://receiver.example.org/api/ingest"
$env:NEXUS_SYNC_AUTH_TOKEN = "..."     # optional; never put credentials in application.yml
cd backend; mvn spring-boot:run
```

Records are marked *synchronized* only when the destination acknowledges them by
id; failures keep data queued and are retried with bounded exponential backoff;
retries are idempotent (stable record ids). **Locally stored ≠ synchronized.**
No-destination try-out: `node tools\sync-test-receiver.mjs` (development aid only).

## Testing done so far

> Module 3: limitations are in `docs/sync.md`. Run `mvn clean test` in `backend/`
> and `npm run test` in `frontend/` for current results on your machine; the notes
> below describe the earlier milestone.


- **Frontend**: real `npm install`, `npm run build`, `npm run lint`
  (oxlint), `npm run test` (Vitest, 3/3 passing).
- **Backend**: 17 JUnit tests written — CPU baseline behavior (3), RAM
  immediate value (1), data-validation cases (4), and, for the
  CHECK-constraint incident specifically: `SchemaMigratorTest` (5 tests
  that reproduce the exact legacy schema and prove the migration repairs
  it without data loss) and `MetricSampleRepositoryTest` (4 tests: a fresh
  schema has no CHECK constraint at all, verified against the actual
  generated DDL — not the annotation; every one of the 11 `MetricType`
  values and 6 `AvailabilityStatus` values persists and reads back; and a
  metric_type value with no corresponding Java enum constant yet can still
  be written at the database layer, proving future enum growth needs no
  schema migration). **Not yet compiled** — confirmed Maven Central
  returns 403 from this sandbox. Structural checks done instead:
  brace/paren balance verified across every file, every provider confirmed
  to implement the full `MetricProvider` interface. The real compiler run
  — `mvn clean package`
  — is the next step, on your machine.
"# Nexus-RMM2.0" 
