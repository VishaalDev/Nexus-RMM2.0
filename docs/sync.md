# Module 3 — Offline data synchronization

Nexus-RMM is **offline-first**: telemetry is always written to the local
SQLite database first, and synchronization is a separate, best-effort
background process that copies that data to a remote *destination* when one
is configured and reachable.

> **Locally stored is not synchronized.** A record counts as *synchronized*
> only after the destination returned a well-formed acknowledgement that
> lists that record's id. Nothing else — not a successful TCP connection, not
> an HTTP 200 without an acknowledgement body, not the passage of time — ever
> marks a record synchronized. With no destination configured, the synchronized
> count is, truthfully, zero.

There was **no pre-existing remote destination or sync code** in the project
(Module 1/2 only mention sync in the roadmap), so Module 3 defines a small,
configurable client and a documented contract. Nexus-RMM ships **no server**;
see "Trying it locally" for an optional development receiver.

## Architecture

```
 collectors (Module 1) ──► metric_sample / hardware_event   (unchanged tables, unchanged code)
                                   │  watermark (cursor.*), copied in bounded chunks
                                   ▼
                              sync_outbox   one small row per record: PENDING → SYNCED | FAILED
                                   │
   SyncScheduler (tick) ─► SyncCoordinator ─► single "nexus-sync" thread ─► SyncEngine
                                                                     │  bounded batches
                                                                     ▼
                                                      HttpSyncClient ──HTTP POST──► destination
```

* **The collector is never modified and never calls sync code.** Sync reads the
  telemetry tables; it does not write to them.
* **Separate thread.** The shared Spring scheduler thread (which also runs
  collection and event polling) only hands work to the `nexus-sync` thread and
  returns immediately, so a slow or hanging destination cannot delay
  collection. `GET /api/sync/status` and the Health/Risk pages never wait on it.
* **Watermark queueing.** `sync_state` stores, per source table, the highest
  row id already copied into `sync_outbox`. Every stored row (including
  history from before Module 3, unless `backfill-existing-data: false`) is
  queued exactly once. The collector's writes are never blocked for long: the
  queue is filled in `enqueue-chunk-size` chunks, each in a short transaction.
* **One run at a time.** A manual trigger during a run is rejected
  (`ALREADY_RUNNING`), never run in parallel.

### Record states

| State | Meaning |
|---|---|
| `PENDING` | Stored locally; not acknowledged. Includes rows not yet copied to the queue. Retried indefinitely. |
| `SYNCED` | The destination explicitly acknowledged this record id. |
| `FAILED` | The destination explicitly refused this record permanently (`rejected`, not retryable), or the local row cannot be represented faithfully (see below). Kept for inspection; never resent automatically. |

Records are **never deleted** by synchronization, and a failed request never
discards or alters data.

## Payload contract (schema version 1)

`POST <destination-url>` with `Content-Type: application/json; charset=UTF-8`.

Headers: `Authorization: Bearer <token>` (only if a token is configured),
`X-Nexus-Schema-Version: 1`, `X-Nexus-Batch-Id: <batchId>`.

```json
{
  "schemaVersion": 1,
  "batchId": "5f0c6d0e-6a40-4f7e-9d6e-0a3a4f6c1b11",
  "sentAt": "2026-10-22T10:00:00.123Z",
  "device": { "computerId": "9f1c…", "hostname": "DESKTOP-01" },
  "records": [
    {
      "recordId": "9f1c…/METRIC_SAMPLE/1042",
      "type": "METRIC_SAMPLE",
      "payload": {
        "computerId": "9f1c…", "metricType": "CPU_UTILIZATION", "instanceLabel": null,
        "timestamp": "2026-10-22T09:59:50.004Z", "timestampEpochMs": 1792663190004,
        "value": 0.0, "unit": "%", "availabilityStatus": "AVAILABLE",
        "source": "OSHI/CentralProcessor", "diagnostic": null
      }
    },
    {
      "recordId": "9f1c…/METRIC_SAMPLE/1043",
      "type": "METRIC_SAMPLE",
      "payload": { "…": "…", "metricType": "CPU_TEMPERATURE", "value": null,
                   "availabilityStatus": "UNAVAILABLE", "diagnostic": "No sensor reported." }
    },
    {
      "recordId": "9f1c…/HARDWARE_EVENT/17",
      "type": "HARDWARE_EVENT",
      "payload": {
        "computerId": "9f1c…", "windowsEventId": 7, "providerName": "disk", "windowsRecordId": 4242,
        "level": "Error", "category": "DISK",
        "occurredAt": "…", "occurredAtEpochMs": 0, "storedAt": "…", "storedAtEpochMs": 0,
        "message": "…"
      }
    }
  ]
}
```

Fidelity rules:

* `payload` carries the **originally stored** values. Timestamps are the
  collection time, in UTC (`timestamp` ISO-8601 and `timestampEpochMs`); they are
  never replaced by the send time (`sentAt` is separate).
* **Unavailable ≠ zero.** An unavailable reading is `"value": null` together with
  its `availabilityStatus` (`UNAVAILABLE`, `UNSUPPORTED`, `PERMISSION_REQUIRED`,
  `NOT_APPLICABLE`, `ERROR`) and `diagnostic`. A real zero is `"value": 0.0`
  with `AVAILABLE`. Null fields are always present as explicit JSON `null`.
* A row whose stored timestamp is not canonical epoch milliseconds, or whose
  value is not finite, is **not sent** (sending it would misstate the data); it
  is marked `FAILED` locally with the reason rather than altered.
* Batches contain at most `batch-size` records (1–1000).

## Acknowledgement contract

The destination must answer with HTTP **2xx** and a JSON body:

```json
{
  "schemaVersion": 1,
  "batchId": "5f0c6d0e-6a40-4f7e-9d6e-0a3a4f6c1b11",
  "acknowledged": ["9f1c…/METRIC_SAMPLE/1042", "9f1c…/HARDWARE_EVENT/17"],
  "rejected": [
    { "recordId": "9f1c…/METRIC_SAMPLE/1043", "reason": "schema violation", "retryable": false }
  ]
}
```

* `acknowledged` (required array of strings): ids the destination has **durably
  stored**. Only these become `SYNCED`. Ids that were not in the request are ignored.
* `rejected` (optional): `retryable: false` (the default) → `FAILED`, not resent;
  `retryable: true` → stays `PENDING`.
* A record in neither list stays `PENDING` and is sent again in a later batch
  (**partial acknowledgement**). Acknowledged records are never resent.
* If `batchId` is present it must match the request, otherwise the response is
  invalid.
* **No acknowledgement, no success.** An empty body, non-JSON body, a missing
  `acknowledged` array, `204`, or a `2xx` that acknowledges nothing is treated as a
  failure; every record in that batch stays `PENDING`.

## Failure handling, retries and backoff

| Situation | Category | Records |
|---|---|---|
| Connection refused / network down | `CONNECTION_FAILED` | stay `PENDING` |
| Host name not found | `DNS_FAILURE` | stay `PENDING` |
| No complete response within `request-timeout` | `TIMEOUT` (also HTTP 408) | stay `PENDING` |
| TLS handshake failure | `TLS_FAILURE` | stay `PENDING` |
| HTTP 401 / 403 | `AUTH_FAILED` | stay `PENDING` |
| HTTP 404 / 405 | `ENDPOINT_NOT_FOUND` | stay `PENDING` |
| HTTP 429 (honours `Retry-After` seconds, capped) | `RATE_LIMITED` | stay `PENDING` |
| HTTP 5xx | `SERVER_ERROR` | stay `PENDING` |
| HTTP 400 / 413 / 422 for the whole batch | `REQUEST_REJECTED` | stay `PENDING` (lower `batch-size`; per-record refusals belong in `rejected`) |
| Redirect or other status | `UNEXPECTED_HTTP_STATUS` | stay `PENDING` (redirects are never followed) |
| Bad/missing acknowledgement body | `INVALID_RESPONSE` | stay `PENDING` |
| 2xx acknowledging nothing | `NOT_ACKNOWLEDGED` | stay `PENDING` |
| Local queue unreadable | `STORAGE_ERROR` | unchanged; monitoring unaffected |

Retry delay after consecutive failed cycles is
`min(backoff-max, backoff-initial × backoff-multiplier^(failures−1))` (defaults
5 s, 5 min, ×2: 5 s, 10 s, 20 s, … 5 min). A longer `Retry-After` is honoured
up to `backoff-max`. A cycle that acknowledged some records resets the delay to
`backoff-initial`; a fully successful cycle clears it. The failure count and next
attempt time are stored in SQLite, so backoff survives a restart. Automatic
attempts wait for the backoff; **Sync Now ignores it**. There is no jitter (a
single client has no herd to spread).

### Idempotency

`recordId = <computerId>/<TYPE>/<local row id>` is stable: identical on every
retry, after restarts, and for the lifetime of the row. The destination **must
deduplicate by `recordId`** and acknowledge duplicates again. This makes the
unavoidable edge cases safe: if the response is lost, or the application crashes
after the destination stored a batch but before the local `SYNCED` update, the
records are simply resent and re-acknowledged, with no duplicates stored.
(Guarantee: *at-least-once delivery, effectively-once storage* given an idempotent
destination.)

## Configuration

`application.yml` (`nexus.sync.*`) or environment variables (Spring relaxed binding):

| Property | Env var | Default | Notes |
|---|---|---|---|
| `destination-url` | `NEXUS_SYNC_DESTINATION_URL` | *(blank)* | blank → `NOT_CONFIGURED` |
| `auth-token` | `NEXUS_SYNC_AUTH_TOKEN` | *(none)* | bearer token; never logged or returned by the API |
| `batch-size` | | 200 | 1–1000 |
| `connect-timeout` | | `PT5S` | |
| `request-timeout` | | `PT30S` | overall deadline per request, including reading the body |
| `backoff-initial` / `backoff-max` / `backoff-multiplier` | | `PT5S` / `PT5M` / 2.0 | |
| `max-batches-per-cycle` | | 50 | bounds work per attempt |
| `enqueue-chunk-size` | | 5000 | rows per short queue-fill transaction |
| `poll-interval-seconds` | | 10 | how often an automatic attempt is considered |
| `backfill-existing-data` | | true | also sync telemetry that existed before Module 3 |

Credentials in the URL (`https://user:pass@…`) are rejected; use `auth-token`.
A missing destination is **not an error** — it is `NOT_CONFIGURED`. An invalid
value (bad URL, `batch-size: 0`, …) is reported as `CONFIG_INVALID` with the exact
reason. Neither stops the application from starting or local monitoring from
working. Plain `http://` to a non-local host with a token produces a warning.

## Status model (what the Sync page shows)

| `state` | Meaning |
|---|---|
| `NOT_CONFIGURED` | No destination. Nothing is sent. Local monitoring unaffected. |
| `CONFIG_INVALID` | Destination/tunables invalid; reason in `configuration.issue`. |
| `SYNCING` | A run is in progress (or accepted and starting). |
| `RETRYING` | Last attempt failed or partly failed; backing off until `nextAttemptAt`. |
| `PENDING` | Data waiting, no failure outstanding. |
| `UP_TO_DATE` | Nothing pending; data has been acknowledged before. |
| `IDLE` | Configured, but no telemetry exists yet. |

`connectivity` is derived from the **last real attempt**, not a probe:
`NOT_CONFIGURED`, `UNKNOWN` (no attempt yet — never claimed reachable),
`REACHABLE` (an HTTP answer was received, even an error), `UNREACHABLE` (no HTTP
answer). Queue counts are exact: `pending` includes rows stored locally but not yet
copied to the queue, so it is right even before the first run and while
unconfigured.

## Trying it locally (development aid only)

`tools/sync-test-receiver.mjs` is a ~70-line Node script that implements this
contract, writes received records to a `.jsonl` file and acknowledges only what it
wrote. It is **not part of the product** and exists only so you can watch real
end-to-end synchronization without a cloud service:

```powershell
node tools\sync-test-receiver.mjs --port 9090 --token demo --fail-first 2 --ack-fraction 0.5
$env:NEXUS_SYNC_DESTINATION_URL = "http://localhost:9090/ingest"
$env:NEXUS_SYNC_AUTH_TOKEN = "demo"
cd backend; mvn spring-boot:run
```

`--fail-first N` simulates an outage (HTTP 503); `--ack-fraction` simulates partial
acknowledgement. Stop the script to simulate the destination being down.

## Known limitations

* **No built-in destination.** You must supply a receiver implementing the contract.
* **Destination changes are not tracked.** `SYNCED` means "acknowledged by whichever
  destination was configured at the time". If you point the app at a different
  destination, already-acknowledged records are not resent. (Resetting is a manual
  SQL operation: `DELETE FROM sync_outbox; DELETE FROM sync_state WHERE state_key LIKE 'cursor.%';`.)
* **FAILED records are not retried automatically** and there is no endpoint/UI to
  requeue them. To retry after fixing the cause:
  `UPDATE sync_outbox SET status='PENDING', last_error=NULL WHERE status='FAILED';`
* **No retention/deletion.** The queue and telemetry grow without bound (retention is a
  separate, still-unimplemented feature). The watermark assumes telemetry rows are
  never deleted and row ids never reused; a future retention feature must account
  for the queue.
* A poison record cannot be skipped unless the destination lists it in `rejected`.
  A whole-batch `400/413/422` keeps data pending and keeps failing until configuration
  or the destination is fixed.
* A hang while the destination streams a response body is bounded by `request-timeout`,
  but response bodies are read fully into memory (limit 1 MiB checked after receipt).
* `Retry-After` HTTP-date form is ignored (seconds form is honoured).
* Counting an un-queued backlog uses an exact `COUNT` per table; with millions of
  un-queued rows `GET /api/sync/status` can take tens of milliseconds.
* The sync tables use the same SQLite file as telemetry; heavy simultaneous writes
  can hit SQLite's single-writer lock (default 3 s busy timeout). Sync writes are
  short, bounded transactions.
* There is no authentication on the Nexus-RMM API itself (unchanged from earlier modules;
  it is intended for `localhost`).
