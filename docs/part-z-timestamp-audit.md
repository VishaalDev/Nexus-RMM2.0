# Part Z — Timestamp audit

**Question.** Module 1 fixed `metric_sample` timestamps (V1 rebuild, V2
normalization). Is any other `Instant` column exposed to the same class of
defect?

## Method
1. Enumerated every `Instant` attribute: `MetricSample.timestamp`,
   `HardwareEvent.occurredAt/storedAt`, `Computer.firstSeenAt/lastSeenAt`.
2. Noted that `InstantEpochMillisConverter` is `autoApply = true`, so **all**
   of them are read with `ResultSet.getLong()`.
3. **Probed the project's own driver** (sqlite-jdbc 3.45.3.0) with legacy
   representations in a `timestamp`-declared column.
4. Inspected a copy of the shipped development database
   (`backend/data/nexus-rmm.db`).

## Findings
| # | Finding | Evidence |
|---|---|---|
| 1 | `getLong()` on legacy **text** does not throw — it silently returns the leading digits. `"2026-09-20 10:00:00.277"` reads back as **`1970-01-01T00:00:02.026Z`**. Worse than the original "Error parsing time stamp": nothing is reported. | Probe output; reproduced in `TimestampAuditMigrationTest.legacyTextReadThroughTheConverterPathSilentlyBecomes1970` |
| 2 | V2 covered only `metric_sample`. `hardware_event` and `computer` had no repair path. | Source review |
| 3 | On the audited database every column is already canonical: `metric_sample` 7,930 rows (digit strings), `computer` INTEGER epoch millis, `hardware_event` empty. **No data was damaged; this is a latent exposure, not an active corruption.** | DB inspection |
| 4 | V2 aborted on an integral decimal (`"1758362400000.0"`, e.g. a REAL under TEXT affinity). | Source review + test |
| 5 | A canonical-looking integer can still be wrong (epoch **seconds** stored as millis ⇒ a 1970 date). A migration cannot know the intent. | Probe (`1758362400` → 1970-01-21) |
| 6 | `metric_sample.timestamp` is TEXT-typed (V1). Range filters work because every value is 13 digits (until year 2286); bucket SQL uses `CAST(timestamp AS INTEGER)` so it works for both TEXT and INTEGER columns. | Tests in `HealthSqlTest` |

## Changes (additive except the listed files)
- **New** `TimestampNormalizer` — one shared implementation of accepted formats and the refuse-to-guess rule.
- **New** `V3NormalizeHardwareEventAndComputerTimestamps` — same contract as V2: every row of all four columns is parsed *before* any UPDATE (one bad value aborts everything, nothing changes), canonical rows untouched, row counts verified. Implausible canonical values (outside 2000–2100) are **logged, never rewritten** (finding 5).
- `SchemaMigrator` — registers V3; adds a default `Migration.applicable()` hook. A migration whose target tables do not exist yet is **skipped and not recorded**, and applied on the first start after Hibernate creates them. Applied-state is now tracked by membership, not `MAX(version)`, so a skipped migration can never be orphaned by a later one. *This is why Module 1's two `MAX(version) == 2` assertions still hold on their legacy-only fixtures, unmodified.*
- `V2NormalizeMetricSampleTimestamps` — delegates parsing to `TimestampNormalizer`. Behavior for every previously-accepted input is identical; it additionally accepts finding 4's integral decimals. (V2 is already applied on existing installs, so this cannot re-run there.)
- `HardwareEvent` — explicit `@Convert(InstantEpochMillisConverter)` on both columns. Same mapping as the inherited autoApply; stated explicitly so it can never silently fall back to a temporal mapping.

## Not changed (deliberately)
- `Computer` relies on autoApply (verified working: stored as INTEGER). Making it explicit was outside the files you asked me to touch.
- No attempt to reinterpret epoch-seconds values.
