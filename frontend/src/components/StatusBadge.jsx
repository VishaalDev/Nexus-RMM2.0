const OK = "var(--color-success)";
const WARN = "var(--color-warning)";
const BAD = "var(--color-critical)";
const NEUTRAL = "var(--color-text-muted)";

const STATUS = {
  // Sensor capability model (AvailabilityStatus in the backend)
  AVAILABLE: { color: OK, label: "Available" },
  UNAVAILABLE: { color: WARN, label: "Unavailable" },
  UNSUPPORTED: { color: NEUTRAL, label: "Unsupported" },
  PERMISSION_REQUIRED: { color: WARN, label: "Permission required" },
  NOT_APPLICABLE: { color: NEUTRAL, label: "Not applicable" },
  ERROR: { color: BAD, label: "Error" },
  // Connectivity / adapter state
  ONLINE: { color: OK, label: "Online" },
  OFFLINE: { color: BAD, label: "Offline" },
  UP: { color: OK, label: "Up" },
  DOWN: { color: NEUTRAL, label: "Down" },
  // Utilization level (presentation only, see utils/metrics.js)
  HIGH: { color: BAD, label: "High utilization" },
  ELEVATED: { color: WARN, label: "Elevated" },
  NORMAL: { color: OK, label: "Normal" },
  // Synchronization (Module 3)
  UP_TO_DATE: { color: OK, label: "Up to date" },
  SYNCING: { color: "var(--color-accent)", label: "Synchronizing" },
  PENDING: { color: WARN, label: "Pending" },
  RETRYING: { color: WARN, label: "Retrying" },
  IDLE: { color: NEUTRAL, label: "Idle (no data yet)" },
  NOT_CONFIGURED: { color: NEUTRAL, label: "Not configured" },
  CONFIG_INVALID: { color: BAD, label: "Configuration invalid" },
  REACHABLE: { color: OK, label: "Reachable" },
  UNREACHABLE: { color: BAD, label: "Unreachable" },
  UNKNOWN: { color: NEUTRAL, label: "Unknown (no attempt yet)" },
  SUCCESS: { color: OK, label: "Succeeded" },
  PARTIAL: { color: WARN, label: "Partially acknowledged" },
  FAILED: { color: BAD, label: "Failed" },
  // Windows event levels
  CRITICAL: { color: BAD, label: "Critical" },
  WARNING: { color: WARN, label: "Warning" },
};

function humanize(status) {
  const text = String(status).toLowerCase().replace(/_/g, " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** Color is never the only signal: a text label always accompanies the dot. */
export default function StatusBadge({ status, label }) {
  const entry = STATUS[status];
  const color = entry?.color ?? NEUTRAL;
  const text = label ?? entry?.label ?? humanize(status);

  return (
    <span className="badge">
      <span className="badge-dot" aria-hidden="true" style={{ background: color }} />
      <span>{text}</span>
    </span>
  );
}
