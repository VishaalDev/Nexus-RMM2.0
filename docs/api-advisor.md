# Module 4 API

All endpoints are read-only `GET`, JSON, `Instant`s as ISO-8601 UTC, `null` fields
omitted. The server listens on loopback by default (see `advisor.md`).

| Path | Returns |
|---|---|
| `/api/advisor` | `AdvisorReport` — headline, counts, issues (ranked), top actions, limitations |
| `/api/advisor/alerts?limit=50` | `AlertEvent[]`, newest first; `limit` clamped to 1–200 |
| `/api/processes` | `ProcessSnapshot` — always 200; `available:false` with a `note` when there is no usable snapshot |

## `AdvisorReport`
```json
{ "generatedAt": "2026-10-24T10:00:00Z",
  "headline": "1 critical issue and 1 warning need attention.",
  "healthScore": 58, "healthStatus": "DEGRADED", "healthCoverage": 0.77, "riskLevel": "HIGH",
  "criticalCount": 1, "warningCount": 1, "infoCount": 0, "processInsight": true,
  "issues": [ /* Issue */ ], "topActions": [ /* Action */ ], "limitations": ["..."] }
```
`Issue`: `key`, `category` (a health component name or `VISIBILITY`), `severity`
(`INFO|WARNING|CRITICAL`), `title`, `summary`, `sourceCodes`, `evidence` (flat map),
`recommendations` (`code, action, why, effort EASY|MODERATE|ADVANCED, expectedBenefit,
caution?`), `confidence`, `priority`, `firstSeen?`, `lastSeen?`.
`Action`: `issueKey, issueTitle, severity, recommendation`.

## `AlertEvent`
`time, type (NEW|ESCALATED|RESOLVED), issueKey, category, severity, title`

## `ProcessSnapshot`
`capturedAt, available, cpuAvailable, processCount, threadCount, uptimeSeconds?,
totalMemoryBytes?, totalCpuPercent?, topByCpu[], topByMemory[], note`.
`ProcessGroup`: `name, processCount, cpuPercent? (of the whole machine), memoryBytes,
memoryPercent?, threadCount, description?, advice?, systemComponent`.
`cpuPercent` is absent (not 0) until a baseline exists.
