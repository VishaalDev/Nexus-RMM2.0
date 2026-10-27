/**
 * Presentation helpers for the Advisor and Processes pages. Severity,
 * priority and every sentence come from the backend; nothing here
 * re-ranks issues or rewrites advice.
 */

export const EFFORT_META = {
  EASY: { label: "Easy", hint: "A minute or two, no special knowledge" },
  MODERATE: { label: "Moderate", hint: "Several minutes, or a setting worth understanding first" },
  ADVANCED: { label: "Advanced", hint: "Needs care: command-line tools, hardware or a restart-level change" },
};

export const ALERT_TYPE_META = {
  NEW: "New",
  ESCALATED: "Escalated",
  RESOLVED: "Resolved",
};

const CATEGORY_LABELS = {
  CPU: "CPU",
  MEMORY: "Memory",
  STORAGE_CAPACITY: "Storage capacity",
  STORAGE_IO: "Storage I/O",
  THERMAL: "Thermal",
  BATTERY: "Battery",
  NETWORK: "Network",
  RELIABILITY: "Reliability",
  VISIBILITY: "Monitoring coverage",
};

export function categoryLabel(category) {
  return CATEGORY_LABELS[category] ?? String(category ?? "Other");
}

/** Issues that deserve attention now (INFO notes do not count). */
export function attentionCount(report) {
  if (!report) return 0;
  return (report.criticalCount ?? 0) + (report.warningCount ?? 0);
}

/** "for 5 min", "for 2 h 10 min", "for 3 d 4 h"; null when the time is unknown or in the future. */
export function formatAge(isoTime, nowMs = Date.now()) {
  if (!isoTime) return null;
  const then = Date.parse(isoTime);
  if (Number.isNaN(then) || then > nowMs) return null;
  const minutes = Math.floor((nowMs - then) / 60_000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    const rest = minutes % 60;
    return rest ? `${hours} h ${rest} min` : `${hours} h`;
  }
  const days = Math.floor(hours / 24);
  const restHours = hours % 24;
  return restHours ? `${days} d ${restHours} h` : `${days} d`;
}

/** "5 min" -> "Open for 5 min"; "just now" stays "Just detected". */
export function openFor(isoTime, nowMs) {
  const age = formatAge(isoTime, nowMs);
  if (!age) return null;
  return age === "just now" ? "Just detected" : `Open for ${age}`;
}

export function formatUptime(seconds) {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return "N/A";
  const days = Math.floor(seconds / 86_400);
  const hours = Math.floor((seconds % 86_400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days} d ${hours} h`;
  if (hours > 0) return `${hours} h ${minutes} min`;
  return `${minutes} min`;
}

const HIDDEN_EVIDENCE = new Set(["attributionNote", "attribution", "dominant"]);

function words(key) {
  return key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .toLowerCase()
    .replace(/^./, (c) => c.toUpperCase());
}

function formatValue(value) {
  if (typeof value === "number") return Number.isInteger(value) ? String(value) : String(Math.round(value * 10) / 10);
  if (typeof value === "boolean") return value ? "Yes" : "No";
  return String(value);
}

/** Flat, readable evidence rows. Nested values and housekeeping keys are left out. */
export function evidenceEntries(evidence) {
  return Object.entries(evidence ?? {})
    .filter(([k, v]) => !HIDDEN_EVIDENCE.has(k) && v !== null && v !== undefined && typeof v !== "object")
    .map(([key, value]) => ({ key, label: words(key), value: formatValue(value) }));
}
