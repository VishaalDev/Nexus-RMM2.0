import { formatBytes, splitReadingValue } from "./format";
import { readingsOfType, usageLevel } from "./metrics";

/**
 * Headline tiles for the Overview, built ONLY from the real readings in
 * /api/metrics/current. A reading that is not AVAILABLE yields a tile with no
 * number and the backend's diagnostic, never a zero. Sums (network, disk) add
 * only the readings that are available.
 */

const available = (r) => r?.availabilityStatus === "AVAILABLE" && r.value !== null && r.value !== undefined;

function percentTile(key, label, reading, extras = {}) {
  if (!available(reading)) {
    return { key, label, number: null, note: reading?.diagnostic ?? "No reading yet", ...extras };
  }
  const parts = splitReadingValue(reading);
  return {
    key,
    label,
    number: parts.number,
    unit: parts.unit,
    percent: reading.value,
    level: usageLevel(reading.value),
    ...extras,
  };
}

function rateTile(key, label, mainReadings, otherReadings, otherLabel) {
  const main = mainReadings.filter(available);
  const other = otherReadings.filter(available);
  if (main.length === 0 && other.length === 0) {
    const diag = [...mainReadings, ...otherReadings].find((r) => r.diagnostic)?.diagnostic;
    return { key, label, number: null, note: diag ?? "No reading yet" };
  }
  const total = (list) => list.reduce((sum, r) => sum + r.value, 0);
  const rate = (list) => {
    const [number, unit] = `${formatBytes(total(list))}`.split(" ");
    return { number, unit: `${unit}/s` };
  };
  const m = main.length > 0 ? rate(main) : null;
  const o = other.length > 0 ? `${otherLabel} ${formatBytes(total(other))}/s` : null;
  if (!m) return { key, label, number: null, note: "Not reporting", sub: o };
  return { key, label, number: m.number, unit: m.unit, sub: o };
}

export function buildOverviewTiles(current, hardware) {
  const list = current ?? [];
  const cpu = readingsOfType(list, "CPU_UTILIZATION")[0];
  const ram = readingsOfType(list, "RAM_UTILIZATION")[0];
  const temp = readingsOfType(list, "CPU_TEMPERATURE")[0];
  const volumes = readingsOfType(list, "STORAGE_UTILIZATION");
  const battery = readingsOfType(list, "BATTERY_LEVEL").find(available);

  const totalRam = hardware?.memory?.totalBytes;
  const tiles = [
    percentTile("cpu", "CPU", cpu),
    percentTile("memory", "Memory", ram, totalRam > 0 ? { sub: `of ${formatBytes(totalRam)}` } : {}),
  ];

  const usable = volumes.filter(available);
  if (usable.length > 0) {
    const worst = usable.reduce((a, b) => (b.value > a.value ? b : a));
    const where = worst.instanceLabel ?? "volume";
    tiles.push(
      percentTile("storage", "Storage", worst, {
        sub: usable.length > 1 ? `${where} · fullest of ${usable.length}` : where,
      }),
    );
  } else {
    tiles.push(percentTile("storage", "Storage", volumes[0]));
  }

  if (!temp || !available(temp)) {
    tiles.push({ key: "temp", label: "CPU temperature", number: null, note: temp?.diagnostic ?? "No reading yet" });
  } else {
    const parts = splitReadingValue(temp);
    tiles.push({ key: "temp", label: "CPU temperature", number: parts.number, unit: parts.unit });
  }

  tiles.push(
    rateTile(
      "network",
      "Network receive",
      readingsOfType(list, "NETWORK_RX_BYTES_PER_SEC"),
      readingsOfType(list, "NETWORK_TX_BYTES_PER_SEC"),
      "Send",
    ),
    rateTile(
      "disk",
      "Disk read",
      readingsOfType(list, "STORAGE_READ_BYTES_PER_SEC"),
      readingsOfType(list, "STORAGE_WRITE_BYTES_PER_SEC"),
      "Write",
    ),
  );

  if (battery) {
    const parts = splitReadingValue(battery);
    tiles.push({
      key: "battery",
      label: "Battery",
      number: parts.number,
      unit: parts.unit,
      percent: battery.value,
      level: "neutral",
      sub: battery.instanceLabel ?? undefined,
    });
  }
  return tiles;
}

/** Tone of the overall status line: only real findings are coloured; "nothing found" is green only when nothing was left unassessed. */
export function statusTone(report) {
  if (!report) return null;
  if ((report.criticalCount ?? 0) > 0) return "critical";
  if ((report.warningCount ?? 0) > 0) return "warning";
  if ((report.infoCount ?? 0) > 0) return "neutral";
  return "ok";
}
