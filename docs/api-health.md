# Module 2 API

All endpoints are `GET`, JSON, `Instant`s as ISO-8601 UTC strings, `null`
fields **omitted** (matching `spring.jackson.default-property-inclusion:
non_null`). Module 1's liveness endpoint `GET /api/health` is unchanged and
distinct from the paths below.

| Path | Returns |
|---|---|
| `/api/health/summary` | `HealthSummary` — overall + one compact row per component |
| `/api/health/report` | `HealthReport` — everything (this is what the Health page uses) |
| `/api/health/components` | `ComponentHealth[]` |
| `/api/health/components/{component}` | one `ComponentHealth` |
| `/api/risk` | `RiskReport` (the Risk page) |
| `/api/risk/{component}` | one `ComponentRisk` |

`{component}` is case-insensitive and accepts `-` for `_`:
`cpu`, `memory`, `storage-capacity`, `storage-io`, `thermal`, `battery`,
`network`, `reliability`. Anything else → `400` with
`Unknown component: 'gpu'. Valid values: [...]` (same error shape as Module 1).

Health and risk in one response always come from the same snapshot; snapshots
are cached for `nexus.health.cache-ttl-seconds`.

## `ComponentHealth` (scored)
Excerpt of real output (volume filling 5 points/day, currently 75% full):
```json
{
  "component": "STORAGE_CAPACITY", "label": "Storage capacity",
  "score": 92, "status": "HEALTHY", "weight": 1.5,
  "sampleCount": 360, "dataCoverage": 1.0,
  "basis": "Current utilization of each volume (latest of the last 60 min); fill-rate projection informational. No SMART/NVMe drive-health data is collected.",
  "instances": [{
    "instance": "C:\\", "score": 92, "status": "HEALTHY", "sampleCount": 360, "dataCoverage": 1.0,
    "metrics": { "utilization": 75.0, "growthPerDay": 5.001, "daysToFull": 4.999, "trendRSquared": 1.0,
                 "historySpanHours": 167.5, "historyDensity": 1.0, "average": 75.0 },
    "findings": [ /* same objects as below */ ]
  }],
  "findings": [{
    "severity": "WARNING", "code": "STORAGE_FILLING_FAST",
    "message": "C:\\ is growing about 5.0 percentage points per day; at this rate it fills in about 5.0 days (linear projection, R² 1.0).",
    "evidence": { "instance": "C:\\", "daysToFull": 5.0, "growthPerDay": 5.0, "rSquared": 1.0, "spanHours": 167.5 }
  }]
}
```
Component-level `findings` is the union of its instances' findings (so they
appear in both places by design). Instance labels contain backslashes
(`C:\`, `\\.\PHYSICALDRIVE0`) — never put them in a URL path; none of these
endpoints does.

## `ComponentHealth` (no data) — note: no `score` key
```json
{
  "component": "BATTERY", "label": "Battery", "status": "NO_DATA", "weight": 0.75,
  "sampleCount": 0, "dataCoverage": 0.0, "basis": "Battery charge-level usage pattern only …",
  "instances": [{ "status": "NO_DATA", "sampleCount": 0, "dataCoverage": 0.0, "metrics": {}, "findings": [],
                  "noDataReason": "No battery level samples found in the last 7 days (nothing stored yet, or the collector has not run since)." }],
  "findings": [],
  "noDataReason": "No battery level samples found in the last 7 days (nothing stored yet, or the collector has not run since)."
}
```

## `ComponentRisk`
```json
{ "component": "STORAGE_CAPACITY", "label": "Storage capacity", "riskScore": 80, "level": "CRITICAL",
  "confidence": "HIGH", "horizonDays": 4.999,
  "factors": [{ "code": "STORAGE_FILLING", "description": "C:\\ is projected to fill in about 5.0 days at its recent growth rate.",
                "severity": 0.8, "evidence": { "instance": "C:\\", "daysToFull": 4.999 } }] }
```
```json
{ "component": "NETWORK", "label": "Network", "level": "NOT_ASSESSED", "confidence": "LOW", "factors": [],
  "notAssessedReason": "Activity anomalies are not failure indicators; no failure risk is derived from this component." }
```

## Enumerations
- `status`: `HEALTHY | FAIR | DEGRADED | CRITICAL | NO_DATA`
- risk `level`: `LOW | MODERATE | HIGH | CRITICAL | NOT_ASSESSED`
- finding `severity`: `INFO | WARNING | CRITICAL`; `confidence`: `LOW | MEDIUM | HIGH`

The key names above are pinned by `HealthJsonContractTest`.
