const BYTE_UNITS = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
const BYTE_STEP = 1024;
const BIT_UNITS = ["bps", "Kbps", "Mbps", "Gbps", "Tbps"];
const BIT_STEP = 1000;
const NOT_REPORTED = "Not reported";

const isMissing = (v) => v === null || v === undefined || Number.isNaN(v);

/** Binary units (KiB, MiB, GiB), matching how operating systems report capacity. */
export function formatBytes(bytes, digits = 1) {
  if (isMissing(bytes)) return "N/A";
  let value = bytes;
  let unit = 0;
  while (value >= BYTE_STEP && unit < BYTE_UNITS.length - 1) {
    value /= BYTE_STEP;
    unit += 1;
  }
  return `${unit === 0 ? value : value.toFixed(digits)} ${BYTE_UNITS[unit]}`;
}

export function formatRate(bytesPerSecond) {
  if (isMissing(bytesPerSecond)) return "N/A";
  return `${formatBytes(bytesPerSecond)}/s`;
}

/** Link speeds use decimal bits per second (1 Gbps = 1,000 Mbps). */
export function formatBitRate(bitsPerSecond) {
  if (isMissing(bitsPerSecond) || bitsPerSecond <= 0) return NOT_REPORTED;
  let value = bitsPerSecond;
  let unit = 0;
  while (value >= BIT_STEP && unit < BIT_UNITS.length - 1) {
    value /= BIT_STEP;
    unit += 1;
  }
  const text = Number.isInteger(value) ? String(value) : value.toFixed(1);
  return `${text} ${BIT_UNITS[unit]}`;
}

export function formatFrequency(hz) {
  if (isMissing(hz) || hz <= 0) return NOT_REPORTED;
  return `${(hz / 1e9).toFixed(2)} GHz`;
}

export function formatMegahertz(mhz) {
  if (isMissing(mhz) || mhz <= 0) return NOT_REPORTED;
  return `${mhz} MHz`;
}

export function formatPercent(value, digits = 1) {
  if (isMissing(value)) return "N/A";
  return `${value.toFixed(digits)}%`;
}

export function formatDateTime(iso) {
  if (!iso) return "N/A";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "N/A" : date.toLocaleString();
}

export function formatTime(iso) {
  if (!iso) return "N/A";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "N/A" : date.toLocaleTimeString();
}

/** "3d 4h 12m" since the given ISO boot time. */
export function formatUptime(bootIso, nowMs = Date.now()) {
  if (!bootIso) return null;
  const boot = new Date(bootIso).getTime();
  if (Number.isNaN(boot) || boot > nowMs) return null;
  const totalMinutes = Math.floor((nowMs - boot) / 60_000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

/** Splits a metric reading into a display number and unit; null when there is no real value. */
export function splitReadingValue(reading) {
  if (!reading || isMissing(reading.value)) return null;
  switch (reading.unit) {
    case "%":
      return { number: reading.value.toFixed(1), unit: "%" };
    case "\u00B0C":
      return { number: reading.value.toFixed(1), unit: "\u00B0C" };
    case "B/s": {
      const [number, unit] = formatRate(reading.value).split(" ");
      return { number, unit };
    }
    default:
      return { number: String(reading.value), unit: reading.unit ?? "" };
  }
}

/** Compact stamp for logs: time only for today, otherwise "Oct 24, 10:00". */
export function formatCompactDateTime(iso, nowMs = Date.now()) {
  if (!iso) return "N/A";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "N/A";
  const sameDay = date.toDateString() === new Date(nowMs).toDateString();
  return sameDay
    ? date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    : date.toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}
