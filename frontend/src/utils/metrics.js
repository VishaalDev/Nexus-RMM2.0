/**
 * Display helpers for the /api/metrics/current payload.
 *
 * Nothing here computes health or risk (that belongs to Module 2). The
 * utilization "level" below is presentation only: it colors and labels a
 * real reported percentage so a nearly-full disk is visible at a glance.
 */

export const METRIC_LABELS = {
  CPU_UTILIZATION: "CPU utilization",
  CPU_TEMPERATURE: "CPU temperature",
  RAM_UTILIZATION: "RAM utilization",
  BATTERY_LEVEL: "Battery level",
  STORAGE_UTILIZATION: "Storage utilization",
  STORAGE_READ_BYTES_PER_SEC: "Storage read throughput",
  STORAGE_WRITE_BYTES_PER_SEC: "Storage write throughput",
  NETWORK_RX_BYTES_PER_SEC: "Network receive (RX)",
  NETWORK_TX_BYTES_PER_SEC: "Network transmit (TX)",
  GPU_UTILIZATION: "GPU utilization",
  GPU_TEMPERATURE: "GPU temperature",
};

/** Order of rows in the live monitoring table. GPU is shown on the Hardware page. */
export const LIVE_TABLE_METRICS = [
  "CPU_UTILIZATION",
  "CPU_TEMPERATURE",
  "RAM_UTILIZATION",
  "BATTERY_LEVEL",
  "STORAGE_UTILIZATION",
  "STORAGE_READ_BYTES_PER_SEC",
  "STORAGE_WRITE_BYTES_PER_SEC",
  "NETWORK_RX_BYTES_PER_SEC",
  "NETWORK_TX_BYTES_PER_SEC",
];

/** Percentages where a HIGH value is the concern (battery is a charge level, not a load). */
export const USAGE_METRICS = new Set([
  "CPU_UTILIZATION",
  "RAM_UTILIZATION",
  "STORAGE_UTILIZATION",
]);

export const USAGE_THRESHOLDS = { elevated: 75, high: 90 };

export function usageLevel(percent) {
  if (percent === null || percent === undefined || Number.isNaN(percent)) return null;
  if (percent >= USAGE_THRESHOLDS.high) return "high";
  if (percent >= USAGE_THRESHOLDS.elevated) return "elevated";
  return "normal";
}

export function readingsOfType(current, type) {
  return (current ?? []).filter((r) => r.metricType === type);
}

/** Finds the reading whose instance label matches any of the given labels (exact match). */
export function findReadingByInstance(readings, ...labels) {
  const wanted = labels.filter(Boolean);
  return readings.find((r) => r.instanceLabel && wanted.includes(r.instanceLabel));
}

/**
 * One row per (metric, instance) for the live table. A metric type with no
 * reading at all yields a single placeholder row (reading: null) so the
 * table says "no sample yet" instead of silently omitting it.
 */
export function buildLiveRows(current) {
  const rows = [];
  for (const type of LIVE_TABLE_METRICS) {
    const readings = readingsOfType(current, type).sort((a, b) =>
      (a.instanceLabel ?? "").localeCompare(b.instanceLabel ?? ""),
    );
    if (readings.length === 0) {
      rows.push({ key: `${type}|none`, type, instance: null, reading: null });
      continue;
    }
    for (const reading of readings) {
      rows.push({
        key: `${type}|${reading.instanceLabel ?? ""}`,
        type,
        instance: reading.instanceLabel,
        reading,
      });
    }
  }
  return rows;
}

/** Chart formatting per metric: how to print a value and what the y-axis range should be. */
export function chartConfigFor(type) {
  if (type === "STORAGE_READ_BYTES_PER_SEC" || type === "STORAGE_WRITE_BYTES_PER_SEC" ||
      type === "NETWORK_RX_BYTES_PER_SEC" || type === "NETWORK_TX_BYTES_PER_SEC") {
    return { unit: "B/s", domain: [0, "auto"], yAxisWidth: 84 };
  }
  if (type === "CPU_TEMPERATURE" || type === "GPU_TEMPERATURE") {
    return { unit: "\u00B0C", domain: ["auto", "auto"], yAxisWidth: 44 };
  }
  return { unit: "%", domain: [0, 100], yAxisWidth: 44 };
}
