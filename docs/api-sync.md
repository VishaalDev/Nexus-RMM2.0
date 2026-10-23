# Module 3 API — synchronization

JSON, `Instant`s as ISO-8601 UTC, `null` fields omitted (same as the other modules).
CORS and error shapes follow the existing conventions. Contract with the remote
destination: [`sync.md`](sync.md). No endpoint returns credentials or record data.

## `GET /api/sync/status`

Cheap, never waits on the network. Always `200`.

```json
{
  "state": "RETRYING",
  "running": false,
  "configuration": {
    "configured": true,
    "destinationSet": true,
    "destination": "https://receiver.example.org/api/ingest",
    "authConfigured": true,
    "warnings": [],
    "batchSize": 200,
    "pollIntervalSeconds": 10
  },
  "connectivity": "UNREACHABLE",
  "connectivityCheckedAt": "2026-10-22T10:00:00Z",
  "queue": {
    "pending": 130, "synced": 5000, "failed": 0, "total": 5130,
    "byType": {
      "METRIC_SAMPLE":  { "pending": 130, "synced": 4990, "failed": 0 },
      "HARDWARE_EVENT": { "pending": 0,   "synced": 10,   "failed": 0 }
    }
  },
  "lastAttemptAt": "2026-10-22T10:00:00Z",
  "lastAttemptResult": "FAILED",
  "lastSuccessfulSyncAt": "2026-10-22T09:40:00Z",
  "consecutiveFailures": 3,
  "nextAttemptAt": "2026-10-22T10:00:20Z",
  "lastError": {
    "category": "CONNECTION_FAILED",
    "message": "Could not connect to https://receiver.example.org/api/ingest",
    "at": "2026-10-22T10:00:00Z",
    "nextStep": "The destination could not be reached. Check that the server is running …"
  },
  "nextStep": "The destination could not be reached. …"
}
```

* `state`: `NOT_CONFIGURED | CONFIG_INVALID | SYNCING | RETRYING | PENDING | UP_TO_DATE | IDLE`.
* `connectivity`: `NOT_CONFIGURED | UNKNOWN | REACHABLE | UNREACHABLE` — from the last real attempt.
* `lastAttemptAt` / `lastSuccessfulSyncAt` are omitted until such an event really happened.
  `lastSuccessfulSyncAt` is the last time at least one record was acknowledged.
* `queue.pending` includes locally stored rows not yet copied into the queue.
* `lastError.category`: see the table in `sync.md`.

## `POST /api/sync/trigger`

"Sync Now". Starts a run on the background sync thread and returns immediately (it
does not wait for the destination). Ignores the retry backoff. No request body.

| Status | `outcome` | Meaning |
|---|---|---|
| `202` | `STARTED` | Accepted; poll `GET /api/sync/status` (`running: true` until it finishes). |
| `409` | `ALREADY_RUNNING` | A run is already in progress. |
| `409` | `NOT_CONFIGURED` | No destination configured; nothing was sent. |
| `409` | `CONFIG_INVALID` | Destination/tunables invalid; see `status.configuration.issue`. |

```json
{ "outcome": "STARTED", "message": "Synchronization started.", "status": { "…": "same shape as GET /api/sync/status" } }
```

`STARTED` only means "a run was accepted", **not** that data was delivered. The result
of the run is visible in `status` (`queue.synced`, `lastAttemptResult`, `lastError`).
