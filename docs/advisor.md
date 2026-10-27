# Module 4 — Advisor: process insight, issues, recommendations, alerts

Modules 1–3 answer *what is happening* and *how healthy is it*. Module 4 answers
**"so what should I do?"** It turns the health analysis, the risk assessment and a
live view of what is running into a short, ranked list of issues, each with the
evidence behind it and concrete steps.

It is **read-only**. Nexus-RMM never kills a process, changes a setting or deletes a
file: every recommendation is a manual step you decide to take.

## Pipeline

```
Module 2 HealthReport + RiskReport ─┐
                                    ├─► AdvisorEngine ─► AlertTracker ─► AdvisorReport ─► /api/advisor
OSHI process table ─► ProcessSampler┘      (rules,        (age, NEW /
   (grouped by application)              correlation,     ESCALATED /
                                          ranking)        RESOLVED)
```

Everything except the thin Spring/OSHI wiring is framework-free (like Module 2), so
it is unit-tested without a database or a web server.

| Class | Role |
|---|---|
| `process.ProcessAggregator` | Groups raw processes by executable, normalises CPU to the whole machine, ranks. Pure. |
| `process.ProcessKnowledge` | Small catalogue of well-known processes (what they are, first thing to check). Unknown names get no annotation. |
| `service.OshiProcessSource` | Reads pid, name, resident size, thread count and per-process CPU tick deltas through the shared OSHI instance. |
| `service.ProcessSamplerService` | Samples every `process-interval-seconds`, keeps only the latest snapshot in memory. |
| `engine.AdvisorEngine` | The rules: findings → issues, cross-signal correlation, priority. Pure and stateless. |
| `engine.RecommendationCatalog` | The advice text, kept apart from the rules that choose it. |
| `engine.AlertTracker` | Issue age and the NEW / ESCALATED / RESOLVED log, with flap control. |
| `service.AdvisorFacade` | Evaluates, caches (10 s), builds the report; `AdvisorScheduler` evaluates every minute so the log stays current while the UI is closed. |

## What it recognises

| Issue key | Derived from | Cross-signal reasoning |
|---|---|---|
| `cpu:load` | `CPU_SUSTAINED_*`, `CPU_SATURATED`, `CPU_FREQUENT_SATURATION` | Names the heaviest application (if ≥ `min-blame-cpu-percent` of the whole CPU), says whether it is "most of what is in use", leads with that application's specific tip, adds a look-it-up step for unknown heavy processes. |
| `memory:load` | `MEMORY_*` equivalents | Names the largest holder in GB and % of RAM; adds a "restart Windows" step when uptime ≥ `long-uptime-days`; suggests more RAM only when pressure is critical and no single program explains it. |
| `cpu:trend`, `memory:trend`, `thermal:trend` | `*_RISING_TREND` | Informational only. |
| `thermal:heat` | `THERMAL_CRITICAL`, `THERMAL_HIGH`, `THERMAL_CRITICAL_EXCURSIONS` | Compares with average CPU load over the same window: ≥ 60% → "most likely load-driven" (reduce load); < 40% → "workload does not explain it" (cooling, dust, airflow). Hedged wording, never "caused by". |
| `storage:<volume>` | `STORAGE_NEARLY_FULL`, `_HIGH_UTILIZATION`, `_FILLING_FAST`, `_FILLING` | Projection (days to full) raises urgency; ≤ 7 days adds an urgent step first. |
| `storage-io:<disk>` | `STORAGE_IO_*_SURGE` | Says per-process disk activity is not collected and points at Resource Monitor. |
| `network:offline`, `network:<adapter>` | `NETWORK_NO_ACTIVE_ADAPTER`, `NETWORK_RX/TX_SURGE` | Unexplained *upload* surge adds a malware-scan step. |
| `battery:<source>` | `BATTERY_*` | |
| `reliability:<category>` | `EVENTS_HARDWARE / STORAGE / POWER / DRIVER / SYSTEM` | Disk errors lead with "back up now" and state that SMART data is not collected; an escalating event rate (from the risk engine) is called out and raises priority. |
| `visibility:warmup`, `visibility:gaps` | components with `NO_DATA` | A missing sensor is reported as "cannot assess", explicitly *not* as healthy. |
| `<component>:<code>` | any other WARNING/CRITICAL finding | Catch-all so a future analyzer's finding is never silently dropped. |

**Priority** is relative, not a probability: severity (300 / 200 / 100) + half the
component's risk score + urgency (projected time-to-event ≤ 7 days / ≤ 30 days,
escalating events) + a small evidence-confidence bonus. The "Do this first" list is
the first step of each of the most urgent distinct issues (WARNING or worse only).

## Alert lifecycle

An issue gets `firstSeen` the first time it is observed in this run. WARNING and
CRITICAL issues log **NEW**; moving up (INFO→WARNING, WARNING→CRITICAL) logs
**ESCALATED**; an issue absent for `resolve-after-absent` consecutive evaluations
(default 2) logs **RESOLVED**. If it comes back sooner it keeps its original age and
nothing is logged, so a flapping metric does not spam the log. INFO notes are tracked
but never alert.

## Privacy and security decisions

* **Command lines, user names and file paths are never read** — they routinely contain
  tokens and personal data, and nothing here needs them. Only program name, resident
  memory, thread count and CPU time are used.
* **Process data is never persisted** and therefore never enters the Module 3 sync
  queue. Only the latest snapshot lives in memory.
* The API has no authentication and now exposes program names, so the backend binds to
  **loopback only** by default (`server.address: ${NEXUS_SERVER_ADDRESS:127.0.0.1}`).
  Set `NEXUS_SERVER_ADDRESS=0.0.0.0` only if you deliberately want other devices to
  reach it — and put authentication in front of it if you do.
* Config that `@Scheduled` reads unchecked (sampling intervals) is range-validated at
  startup so a typo cannot create a busy loop.

## Honesty rules (same spirit as Modules 1–3)

* First process sample has no CPU baseline → CPU is reported **unavailable**, never 0%.
* A failed or stale (> 4 intervals old) process sample is shown as *unavailable with a
  reason*, never as current data.
* Group memory is the sum of resident sets; shared pages can be counted per process, so
  big groups (browsers) may be overstated. The API says so in `note`.
* Process attribution is "right now"; the health finding covers the last hour. The issue
  says they can differ (`attributionNote`).
* Headlines never say "healthy" when little could be assessed.

## Known limitations

* The alert log and issue ages live **in memory** and reset on backend restart.
* No per-process disk or network attribution, no GPU, no SMART (see
  `hardware-monitoring.md`). Advice that needs these says so and points to the OS tool.
* Advice is rule-based; effects of a step cannot be measured before it is taken, so
  benefits are worded modestly. `ProcessKnowledge` is intentionally small (Windows-centric)
  and only contains entries whose description is certain.
* Not yet built: desktop/email notification delivery (the Advisor page and sidebar badge
  are the notification surface), persisted issue history, acting on a recommendation.

## Tuning

All values are in `application.yml` under `nexus.advisor` (see the comments there).
