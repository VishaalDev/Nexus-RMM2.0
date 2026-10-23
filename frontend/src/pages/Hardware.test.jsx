import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, cleanup, within } from "@testing-library/react";
import Hardware from "./Hardware";

vi.mock("../api/services/hardwareService", () => ({ getHardwareSnapshot: vi.fn() }));
vi.mock("../api/services/metricsService", () => ({ getCurrentMetrics: vi.fn(), getMetricHistory: vi.fn() }));

import { getHardwareSnapshot } from "../api/services/hardwareService";
import { getCurrentMetrics } from "../api/services/metricsService";

const snapshot = (batterySource) => ({
  cpu: { name: "Test CPU", vendor: "V", identifier: "id", physicalCores: 4, logicalCores: 8, maxFreqHz: 3_000_000_000 },
  memory: { totalBytes: 16 * 1024 ** 3, modules: [] },
  storageDevices: [{ name: "\\\\.\\PHYSICALDRIVE0", model: "Some NVMe Drive", sizeBytes: 512 * 1024 ** 3 }],
  gpus: [{ name: "Test GPU", vendor: "V", vramBytes: 0 }],
  battery: { present: true, sources: [batterySource] },
  networkAdapters: [],
  volumes: [],
});

const SOURCE = {
  name: "System Battery",
  remainingCapacityPercent: 85.5,
  designCapacity: 50000,
  maxCapacity: 42000,
  capacityUnit: "MWH",
  charging: false,
  powerOnLine: false,
};

describe("Hardware page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getCurrentMetrics.mockResolvedValue([
      { metricType: "GPU_UTILIZATION", instanceLabel: null, value: null, availabilityStatus: "UNSUPPORTED", diagnostic: "GPU telemetry needs a vendor SDK." },
    ]);
  });
  afterEach(() => cleanup());

  it("spells out the battery capacity unit and which number is which", async () => {
    getHardwareSnapshot.mockResolvedValue(snapshot(SOURCE));
    render(<Hardware />);
    const row = (await screen.findByText("Design / maximum capacity")).closest("tr");
    expect(within(row).getByText(/50000 \(design\) \/ 42000 \(current maximum\)/)).toBeInTheDocument();
    expect(within(row).getByText(/mWh \(milliwatt-hours\)/)).toBeInTheDocument();
  });

  it("calls the percentage a charge level, not a health figure", async () => {
    getHardwareSnapshot.mockResolvedValue(snapshot(SOURCE));
    render(<Hardware />);
    const row = (await screen.findByText("Charge level (remaining capacity)")).closest("tr");
    expect(within(row).getByText("85.5%")).toBeInTheDocument();
  });

  it("separates hardware inventory from battery-wear assessment, and does not compute a wear percentage", async () => {
    getHardwareSnapshot.mockResolvedValue(snapshot(SOURCE));
    render(<Hardware />);
    expect(await screen.findByText(/Battery wear and health are not assessed here: no wear percentage is calculated/)).toBeInTheDocument();
    expect(screen.queryByText(/wear:?\s*\d+(\.\d+)?%/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/84(\.0)?%/)).not.toBeInTheDocument(); // 42000/50000 must NOT be derived
  });

  it("shows an unrecognised capacity unit verbatim instead of guessing", async () => {
    getHardwareSnapshot.mockResolvedValue(snapshot({ ...SOURCE, capacityUnit: "FUTURE_UNIT" }));
    render(<Hardware />);
    expect(await screen.findByText(/FUTURE_UNIT/)).toBeInTheDocument();
  });

  it("labels a relative-unit battery as not a physical unit, and an UNKNOWN unit as unavailable", async () => {
    getHardwareSnapshot.mockResolvedValue(snapshot({ ...SOURCE, capacityUnit: "RELATIVE" }));
    const { unmount } = render(<Hardware />);
    expect(await screen.findByText(/relative units \(not a physical unit\)/)).toBeInTheDocument();
    unmount();
    getHardwareSnapshot.mockResolvedValue(snapshot({ ...SOURCE, capacityUnit: "UNKNOWN" }));
    render(<Hardware />);
    const row = (await screen.findByText("Design / maximum capacity")).closest("tr");
    expect(within(row).getByText("Unavailable")).toBeInTheDocument();
  });

  it("preserves the explicit GPU and drive-health limitations", async () => {
    getHardwareSnapshot.mockResolvedValue(snapshot(SOURCE));
    render(<Hardware />);
    expect(await screen.findByText(/Unsupported .* real-time GPU telemetry not integrated/)).toBeInTheDocument();
    expect(screen.getByText("GPU telemetry needs a vendor SDK.")).toBeInTheDocument();
    expect(screen.getByText(/SMART and NVMe drive-health attributes are not collected/)).toBeInTheDocument();
  });
});
