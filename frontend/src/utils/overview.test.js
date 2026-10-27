import { describe, it, expect } from "vitest";
import { buildOverviewTiles, statusTone } from "./overview";

const reading = (metricType, value, extra = {}) => ({
  metricType,
  instanceLabel: null,
  value,
  unit: "%",
  availabilityStatus: "AVAILABLE",
  diagnostic: null,
  ...extra,
});

const byKey = (tiles, key) => tiles.find((t) => t.key === key);

describe("buildOverviewTiles", () => {
  it("never turns an unavailable reading into a number; it carries the backend's reason", () => {
    const tiles = buildOverviewTiles([
      reading("CPU_UTILIZATION", null, { availabilityStatus: "UNAVAILABLE", unit: null, diagnostic: "Establishing baseline." }),
    ]);
    const cpu = byKey(tiles, "cpu");
    expect(cpu.number).toBeNull();
    expect(cpu.percent).toBeUndefined();
    expect(cpu.note).toBe("Establishing baseline.");
  });

  it("shows the fullest volume and says how many were considered", () => {
    const tiles = buildOverviewTiles([
      reading("STORAGE_UTILIZATION", 41.2, { instanceLabel: "D:\\" }),
      reading("STORAGE_UTILIZATION", 93.4, { instanceLabel: "C:\\" }),
    ]);
    const storage = byKey(tiles, "storage");
    expect(storage.number).toBe("93.4");
    expect(storage.level).toBe("high");
    expect(storage.sub).toBe("C:\\ · fullest of 2");
  });

  it("adds throughput only from available adapters", () => {
    const tiles = buildOverviewTiles([
      reading("NETWORK_RX_BYTES_PER_SEC", 1024, { unit: "B/s", instanceLabel: "eth0" }),
      reading("NETWORK_RX_BYTES_PER_SEC", 1024, { unit: "B/s", instanceLabel: "wlan0" }),
      reading("NETWORK_RX_BYTES_PER_SEC", null, { unit: null, instanceLabel: "tun0", availabilityStatus: "UNAVAILABLE" }),
      reading("NETWORK_TX_BYTES_PER_SEC", 512, { unit: "B/s", instanceLabel: "eth0" }),
    ]);
    const net = byKey(tiles, "network");
    expect(net.number).toBe("2.0");
    expect(net.unit).toBe("KiB/s");
    expect(net.sub).toBe("Send 512 B/s");
  });

  it("omits the battery tile when there is no battery reading, and keeps a neutral level when there is", () => {
    expect(byKey(buildOverviewTiles([]), "battery")).toBeUndefined();
    const withBattery = buildOverviewTiles([reading("BATTERY_LEVEL", 64, { instanceLabel: "BAT0" })]);
    expect(byKey(withBattery, "battery").level).toBe("neutral");
  });

  it("reports the installed RAM as context, not as a derived usage figure", () => {
    const tiles = buildOverviewTiles([reading("RAM_UTILIZATION", 50)], { memory: { totalBytes: 17179869184 } });
    expect(byKey(tiles, "memory").sub).toBe("of 16.0 GiB");
  });
});

describe("statusTone", () => {
  it("is green only when nothing was found and nothing was left unassessed", () => {
    expect(statusTone({ criticalCount: 0, warningCount: 0, infoCount: 0 })).toBe("ok");
    expect(statusTone({ criticalCount: 0, warningCount: 0, infoCount: 1 })).toBe("neutral");
    expect(statusTone({ criticalCount: 0, warningCount: 2, infoCount: 0 })).toBe("warning");
    expect(statusTone({ criticalCount: 1, warningCount: 2, infoCount: 0 })).toBe("critical");
    expect(statusTone(null)).toBeNull();
  });
});
