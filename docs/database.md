# Database

SQLite via `org.xerial:sqlite-jdbc`, dialect `org.hibernate.community.dialect.SQLiteDialect`
(from `hibernate-community-dialects` — Hibernate 6's core artifact does not
ship a SQLite dialect). `spring.jpa.hibernate.ddl-auto: update` handles
additive changes (new tables, new columns); anything SQLite cannot express
as `ALTER TABLE ADD COLUMN` goes through the versioned migration runner
described below instead.

## Tables (current)

| Table | Purpose |
|---|---|
| `computer` | Single machine-identity row (Module 1) |
| `metric_sample` | Time-series telemetry, one row per (metric type, instance, timestamp) |
| `hardware_event` | Normalized Windows System-log entries (Module 1) |
| `schema_migration` | Version-tracked record of applied raw-SQL migrations (see below) |

## Incident: SQLITE_CONSTRAINT_CHECK on new metric types

**What happened.** Hibernate 6.2+ auto-generates a SQL `CHECK` constraint
listing every current enum constant for any `@Enumerated(EnumType.STRING)`
column, at the moment it first creates the table. `metric_sample.metric_type`
was first created when `MetricType` had 8 values. When the enum grew to 11
(adding `NETWORK_TX_BYTES_PER_SEC`, `NETWORK_RX_BYTES_PER_SEC`,
`STORAGE_READ_BYTES_PER_SEC`, `STORAGE_WRITE_BYTES_PER_SEC`), the CHECK
constraint did not grow with it — `ddl-auto: update` only emits additive
DDL (new columns/tables) and does not revisit existing constraints, and
even if it tried to, **SQLite has no `ALTER TABLE` support for modifying a
CHECK constraint at all** (this is a documented SQLite limitation, not a
Hibernate bug — SQLite's own guidance for any constraint change is to
rebuild the table). The result: every insert of one of the 4 new metric
types failed with `SQLITE_CONSTRAINT_CHECK`, while the 7 pre-existing
metric types kept working normally, and the app itself started fine
(schema creation doesn't evaluate CHECK constraints — only inserts do).

**First attempt, which did not work.** An explicit
`@Column(columnDefinition = "VARCHAR(n)")` was tried first, on the theory
that telling Hibernate to treat the column as a raw, user-specified type
would stop it inferring and constraining the type itself. On inspection of
the actual generated schema, this did **not** suppress the constraint under
this project's Hibernate 6.4.4.Final + `hibernate-community-dialects` +
`SQLiteDialect` combination — the fresh schema still contained
`check (metric_type in (...))`. Recorded here so the same dead end isn't
retried later: `columnDefinition` is not a reliable way to disable this
behavior on this stack.

**The permanent fix.** `MetricSample.metricType` and
`MetricSample.availabilityStatus` are now mapped through a plain JPA
`AttributeConverter` (`MetricTypeConverter`, `AvailabilityStatusConverter`)
instead of `@Enumerated(EnumType.STRING)`. This sidesteps the problem at
its root rather than trying to suppress a side effect: a converter presents
the attribute to Hibernate's type system as an ordinary `String` from the
very start, so the enum-specific constraint-generation logic — which is
gated on the attribute being resolved as an enum type — never runs at all.
There is nothing being overridden or suppressed; the code path that
generates the constraint simply never fires, because Hibernate never
resolves the column as an enum in the first place. This was verified by
inspecting the actual `CREATE TABLE` statement Hibernate generates for a
brand-new database (`MetricSampleRepositoryTest`'s "Test B"), not just by
reading the annotation.

Enum validity is still fully enforced at the Java layer: each converter's
`convertToEntityAttribute` calls the enum's own `valueOf`, which throws on
any string that isn't a real constant, so a corrupted or hand-edited row
is caught loudly on read — not silently accepted, and not re-encoded as a
second, hand-maintained SQL constraint (which is exactly the
two-sources-of-truth pattern that caused this incident in the first
place). **This means no future metric type or availability status value
will ever hit this again**, on a freshly created table — proven, not just
argued, by `MetricSampleRepositoryTest`'s "Test D," which writes a
metric_type value that doesn't correspond to any current Java enum
constant directly into the database and confirms it's accepted with no
schema change required.

**The one-time repair for an existing database.** Neither mapping change
retroactively touches a table that already exists — that table still
physically contains the old constraint until it's rebuilt.
`com.nexusrmm.migration.SchemaMigrator` runs a small, dependency-free,
versioned set of raw-JDBC migrations before Spring's ApplicationContext
refreshes (registered via `SchemaMigrationInitializer` in
`NexusRmmApplication.main()`), specifically *before* Hibernate's
`ddl-auto:update` or any repository touches the database. Migration `v1`
(`V1RebuildMetricSampleWithoutEnumCheckConstraints`) detects a
`metric_sample` table whose stored DDL still contains a CHECK constraint
on `metric_type`, and if found:

1. Renames it to `metric_sample_v0_backup`.
2. Creates a fresh `metric_sample` with no CHECK constraint.
3. Copies every row across by explicit column list (not `SELECT *`).
4. Verifies the row count matches before proceeding — if it doesn't, the
   whole migration rolls back (SQLite's DDL is transactional, so this is
   safe) and the backup table is left in place rather than dropped, so no
   data is ever silently lost.
5. Only then drops the backup table and recreates the lookup index.

It is idempotent (checks the live schema before doing anything, and is
gated by a `schema_migration` version row besides) and a no-op on a
database that never had the problem — including a brand-new install,
where `metric_sample` doesn't exist yet at all and Hibernate creates it
correctly, with no constraint, from scratch.

**Why this belongs in `SchemaMigrator` and not just a one-off fix.** The
runner is designed to be the answer for *any* future change SQLite can't
express as an additive `ALTER TABLE` — a column type change, a data
reshape, a constraint change. Each such change becomes one more
`Migration` appended to `SchemaMigrator.migrations()`, tracked by version
in the `schema_migration` table, applied exactly once, in order, on every
existing database. An existing, already-numbered migration is never
edited after it ships — only appended to — so a database that already
applied it is never re-run against a changed definition.

## Incident: "Error parsing time stamp" on GET /api/metrics/current

**What happened.** Hibernate 6 maps `java.time.Instant` to
`SqlTypes.TIMESTAMP_UTC` by default (falling back to plain `TIMESTAMP` for
a dialect with no timezone-aware SQL type — true of
`hibernate-community-dialects`' `SQLiteDialect`). Combined with v1's
rebuild declaring `metric_sample.timestamp` as `TEXT` — a reasonable,
generic choice at the time, made before this specific temporal-type
interaction was understood — Hibernate ended up binding `Instant` values
through a path that SQLite, under TEXT affinity, stored as the raw text
form of a bare epoch-millisecond number for some rows (e.g.
`1790438061277`), while sqlite-jdbc's `ResultSet.getTimestamp()` only
knows how to parse a formatted date-time string, not a bare number. The
result: `GET /api/metrics/current` → `MetricQueryService.currentValues()`
→ `repository.findRecent()` → Hibernate hydrating that row → `getTimestamp()`
→ `Error parsing time stamp`, an HTTP 500.

**The permanent fix.** Same strategy as the enum incident: `MetricSample.timestamp`
is now mapped through `InstantEpochMillisConverter`, a plain JPA
`AttributeConverter<Instant, Long>`, instead of letting Hibernate infer a
temporal SQL type. Hibernate now binds this column via ordinary
`setLong()`/`getLong()` — the entire `TIMESTAMP_UTC`/`getTimestamp()`
machinery is bypassed, so this class of mismatch cannot recur regardless
of what Hibernate's default Instant handling does in a future version.
Epoch milliseconds (not an ISO-8601 string) was chosen deliberately:
`Instant.toString()` omits trailing zero fractional digits, which can sort
a formatted string out of true chronological order under a plain SQL
`ORDER BY` — a plain integer has no such edge case. This is purely an
internal storage detail; API responses still serialize `Instant` fields as
ISO-8601 (Jackson's JavaTimeModule), unaffected by how it's stored.

**The one-time repair.** `V2NormalizeMetricSampleTimestamps` (schema
version 2) reads every row's current raw timestamp value and rewrites it
as canonical epoch milliseconds via `UPDATE`, handling — in order — a bare
epoch-millisecond number (already canonical), a proper ISO-8601 instant
string, and sqlite-jdbc's own default
`PreparedStatement.setTimestamp()` string format
(`"yyyy-MM-dd HH:mm:ss[.SSS]"`, space-separated, no zone). Unlike v1, no
table rebuild is needed — this is a data problem, not a schema-constraint
problem, and a plain `UPDATE` can never change the row count. Every row
must parse under one of the three known formats before any write happens;
if even one doesn't, the whole migration throws and rolls back before
issuing a single `UPDATE`, rather than guessing, discarding, or partially
applying — consistent with v1's fail-loud, preserve-everything behavior.

## Indexes

- `idx_metric_sample_lookup` on `(computer_id, metric_type, instance_label, timestamp)`
- `idx_hardware_event_time` on `(computer_id, occurred_at)`
- Unique constraint on `hardware_event (computer_id, provider_name, record_id)` for idempotent event re-polling

## Synchronization queue (Module 3, migration v4)

Two **new** tables, created by `V4CreateSyncQueueTables` (and re-verified
idempotently at startup with `CREATE TABLE IF NOT EXISTS`). No existing table,
column or row is read, altered or deleted by the migration, and it queues
nothing itself.

```sql
sync_outbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  record_type TEXT NOT NULL,            -- METRIC_SAMPLE | HARDWARE_EVENT
  source_id INTEGER NOT NULL,           -- id of the row in metric_sample / hardware_event
  status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING | SYNCED | FAILED
  attempts INTEGER NOT NULL DEFAULT 0,
  enqueued_at INTEGER NOT NULL,         -- epoch millis, like every other timestamp
  last_attempt_at INTEGER, synced_at INTEGER,
  last_error TEXT,
  UNIQUE (record_type, source_id)       -- a source row is queued at most once
)
CREATE INDEX idx_sync_outbox_status ON sync_outbox (status, id)

sync_state ( state_key TEXT PRIMARY KEY, state_value TEXT, updated_at INTEGER NOT NULL )
-- keys: cursor.METRIC_SAMPLE, cursor.HARDWARE_EVENT (watermarks), lastAttemptAt,
--       lastAttemptResult, lastAttemptReachable, lastSuccessAt, consecutiveFailures,
--       nextAttemptAt, lastErrorCategory/Message/At
```

Design notes and migration safety:

* **Additive only.** Old databases keep working: the app starts, v4 adds the two
  tables, and the existing `metric_sample`/`hardware_event` rows (including history
  from before Module 3) become eligible through the watermark. The database file is
  never replaced or recreated. Rolling back to an older build is safe: it ignores the
  extra tables.
* **Why not columns on `metric_sample`?** It would require changing a Hibernate entity
  and an `ALTER TABLE` on the largest table, and would put sync state on the hot write
  path. A separate queue leaves Module 1 and 2 untouched.
* **Why a watermark + outbox rather than one outbox row per insert?** The collector
  needs no hook and cannot be affected by sync failures; history is covered by the same
  mechanism as new data.
* **No CHECK constraints.** Statuses are plain text validated in code, deliberately,
  because of the stale-CHECK-constraint incident documented above.
* Values and timestamps are copied verbatim at send time from the source rows, which
  stay the single source of truth: an unavailable reading is SQL `NULL` and is sent as
  JSON `null`, a real `0.0` is sent as `0.0`.
* To reset sync state (e.g. a new destination), see "Known limitations" in `sync.md`.
  Telemetry tables are not affected by any of these statements.

## Retention

Not yet implemented. Note for when it is: it must also handle `sync_outbox`, and the
sync watermark assumes telemetry rows are not deleted and row ids are not reused
(see `sync.md`).
