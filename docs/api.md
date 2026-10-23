# API Reference

All endpoints are served from the backend at `http://localhost:8080` (see
CORS config for the allowed dev-server origin). Every timestamp in a
response is ISO-8601 UTC (e.g. `"2026-09-27T12:34:21.277Z"`) regardless of
how it's stored internally.

## GET /api/metrics/history

**This endpoint requires `metric`.** Calling it without one — e.g.
`GET /api/metrics/history` with no query string, or `GET /api/metrics/history?window=1h`
— returns HTTP 400 with:

```
Required request parameter 'metric' for method parameter type String is not present.
```

This is intentional, not a bug: there is no sensible default metric to
return history for (unlike `/api/metrics/current`, which reports every
metric that has data). The parameter is required deliberately and is not
being made optional — a caller must say which series it wants.

### Parameters

| Name       | Required | Values                                   | Default |
|------------|----------|-------------------------------------------|---------|
| `metric`   | **Yes**  | One of the `MetricType` enum names below   | —       |
| `instance` | No       | An instance label (drive letter, NIC name, GPU name) as returned by `/api/metrics/current`, for metrics with more than one instance | none (single-instance metrics) |
| `window`   | No       | `1h`, `6h`, `24h`, `7d`, or `30d`           | `1h`    |

Valid `metric` values (see `MetricType`): `CPU_UTILIZATION`,
`CPU_TEMPERATURE`, `RAM_UTILIZATION`, `STORAGE_UTILIZATION`,
`STORAGE_READ_BYTES_PER_SEC`, `STORAGE_WRITE_BYTES_PER_SEC`,
`GPU_UTILIZATION`, `GPU_TEMPERATURE`, `BATTERY_LEVEL`,
`NETWORK_RX_BYTES_PER_SEC`, `NETWORK_TX_BYTES_PER_SEC`.

`window` is capped at 30 days server-side regardless of what's requested;
a larger value returns HTTP 400. The result is also capped at
`nexus.history.max-points` (default 5000) rows regardless of window size.

### Examples

Single-instance metric (no `instance` needed):
```
GET /api/metrics/history?metric=CPU_UTILIZATION&window=6h
```

Multi-instance metric (a specific volume, disk, adapter, or GPU):
```
GET /api/metrics/history?metric=STORAGE_UTILIZATION&instance=C%3A&window=24h
```
(`instance` values containing special characters, like a Windows drive
letter's colon, must be URL-encoded — `C:` becomes `C%3A`.) Omitting
`instance` for a multi-instance metric returns history for the row whose
`instance_label` is `NULL`, which is usually empty for those metric types
— pass the exact label from `/api/metrics/current` instead.

### Response

```json
[
  { "timestamp": "2026-09-27T12:00:00Z", "value": 42.5, "availabilityStatus": "AVAILABLE" },
  { "timestamp": "2026-09-27T12:00:10Z", "value": null, "availabilityStatus": "UNAVAILABLE" }
]
```

A `null` value with a non-`AVAILABLE` status is a genuine gap (sensor
unavailable that cycle), not missing data — see docs/hardware-monitoring.md.

## GET /api/metrics/current

No required parameters. Returns the latest sample for every
`(metric type, instance)` pair with a fresh reading (within a few
collection intervals). Types/instances with no recent data are simply
absent from the array.

## GET /api/device/hardware

No parameters. Static Hardware DNA snapshot (see docs/hardware-monitoring.md).

## GET /api/diagnostics

No parameters. Live provider capability matrix, storage counts, and
event-monitoring status.

## GET /api/events

| Name    | Required | Default | Max |
|---------|----------|---------|-----|
| `limit` | No       | 100     | 500 |

## GET /api/device / GET /api/health

No parameters.

## Synchronization (Module 3)

`GET /api/sync/status` and `POST /api/sync/trigger` — see [`api-sync.md`](api-sync.md).
