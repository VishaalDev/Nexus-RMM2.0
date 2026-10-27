# Nexus-RMM

**A Lightweight Monitoring System with Offline Data Synchronization**

> Status: **Modules 1 (hardware monitoring), 2 (health analysis & failure
> risk), 3 (offline-first synchronization) and 4 (advisor: process insight,
> prioritized issues, recommendations, alerts) are implemented.** Module 4 is
> what turns the measurements into decisions: it names what is using your
> CPU and memory, ranks what needs attention and says what to do about it.
> See [`docs/advisor.md`](docs/advisor.md).

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

## Module 4 — Advisor (new)

- **Processes**: per-application CPU and memory (all of a browser's processes
  grouped as one row), with a plain-language "what is this" for well-known
  programs. Program names only: command lines, user names and paths are never
  read, and nothing is stored or synchronized.
- **Advisor**: ranked issues, each with the evidence, and concrete manual
  steps. It correlates signals (e.g. "CPU is overloaded **and** chrome.exe is
  62% of it"; "heat with low CPU load points at cooling, not workload"),
  reports a missing sensor as *cannot assess* rather than healthy, and never
  changes anything on your computer.
- **Alerts**: NEW / ESCALATED / RESOLVED log with flap control, a sidebar
  badge and an Overview "Needs attention" panel.

## What's not built yet

- Desktop/e-mail notification delivery and persisted issue history (the alert
  log is in memory; see `docs/advisor.md`)
- Per-process disk/network attribution
- A built-in remote destination: Module 3 synchronizes to a receiver *you*
  provide (contract in `docs/sync.md`); none is bundled or faked
- Frontend pages for Reports and Settings
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
├── backend/     Spring Boot (Java 17), SQLite, 8 OSHI-based providers + event monitor, health/risk engines, sync engine, advisor
├── tools/       sync-test-receiver.mjs — optional dev-only receiver for trying sync locally
├── frontend/    React + Vite, Recharts (Overview, Advisor, Processes, Health, Risk, Storage, Hardware, Network, History, Events, Sync, Diagnostics)
└── docs/
    ├── architecture.md          system-wide design, updated each phase
    ├── hardware-monitoring.md   Module 1 detail + itemized known limitations
    └── advisor.md               Module 4 design, rules, privacy, limitations
```

## Running it

### Backend

Requires JDK 17+ and Maven with access to Maven Central.

```bash
cd backend
mvn clean package
mvn spring-boot:run
```

The API listens on `http://localhost:8080` and, **by default, only on the
loopback interface** (it has no authentication and now exposes program
names). Set `NEXUS_SERVER_ADDRESS=0.0.0.0` only if you deliberately want other
devices to reach it. On first run it creates
`backend/data/nexus-rmm.db` and a `Computer` identity row from your real
system information.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Start with **Advisor** (what needs attention and
what to do) and **Processes** (what is using the machine right now).

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
Module 4 endpoints (`/api/advisor`, `/api/advisor/alerts`, `/api/processes`) are in
`docs/api-advisor.md`.

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

## Testing

```bash
cd backend  && mvn clean test     # JUnit 5
cd frontend && npm run lint && npm run test && npm run build
```

Last verified state of this tree:

- **Frontend** (real run): `npm run lint` 0 warnings, `npm run test` 100/100
  passing, `npm run build` succeeds.
- **Backend, Modules 1-3**: 214/214 tests passed on the project owner's machine
  before Module 4 was added (surefire reports).
- **Backend, Module 4**: 67 new JUnit tests (engine rules, correlation, alert
  lifecycle, facade, settings, process aggregation and sampler). They were run
  against the framework-free classes with `javac` and a minimal JUnit stand-in
  (together with the existing 99 Module 2 analysis/risk tests: 166/166), because
  Maven Central was unreachable where Module 4 was written. The Spring/OSHI
  wiring (`OshiProcessSource`, `AdvisorModuleConfig`, `AdvisorController`, the
  `@Scheduled` beans) was type-checked against stubs only. **Run `mvn clean
  test` and start the backend once** to confirm that part on a real OSHI/Spring
  classpath.
