import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import Overview from "./Overview";

vi.mock("../api/services/deviceService", () => ({
  getBackendHealth: vi.fn(),
  getDevice: vi.fn(),
}));
vi.mock("../api/services/hardwareService", () => ({
  getHardwareSnapshot: vi.fn(),
}));
vi.mock("../api/services/metricsService", () => ({
  getCurrentMetrics: vi.fn(),
  getMetricHistory: vi.fn(),
}));

import { getDevice } from "../api/services/deviceService";
import { getHardwareSnapshot } from "../api/services/hardwareService";
import { getCurrentMetrics, getMetricHistory } from "../api/services/metricsService";

const DEVICE = {
  computerId: "abc-123",
  hostname: "TEST-HOST",
  manufacturer: "Dell Inc.",
  model: "OptiPlex 7090",
  osName: "Windows 11",
  osVersion: "10.0.22631",
};

const HARDWARE = {
  system: {
    hostname: "TEST-HOST",
    manufacturer: "Dell Inc.",
    model: "OptiPlex 7090",
    biosVendor: "Dell Inc.",
    biosVersion: "2.1.0",
    biosReleaseDate: "2025-01-10",
    baseboardManufacturer: "Dell Inc.",
    baseboardModel: "0ABCD1",
    osFamily: "Windows 11",
    osVersion: "10.0.22631",
    osBuild: "22631",
    architecture: "amd64",
    bootTime: new Date(Date.now() - 3_600_000).toISOString(),
  },
};

describe("Overview page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders merged system identity and a real live metric value", async () => {
    getDevice.mockResolvedValue(DEVICE);
    getHardwareSnapshot.mockResolvedValue(HARDWARE);
    getCurrentMetrics.mockResolvedValue([
      {
        metricType: "CPU_UTILIZATION",
        instanceLabel: null,
        value: 37.4,
        unit: "%",
        availabilityStatus: "AVAILABLE",
        source: "OSHI/CentralProcessor",
        diagnostic: null,
        timestamp: new Date().toISOString(),
      },
    ]);
    getMetricHistory.mockResolvedValue([]);

    render(<Overview />);

    await waitFor(() => expect(screen.getAllByText("TEST-HOST").length).toBeGreaterThan(0));
    expect(screen.getByText("abc-123")).toBeInTheDocument();
    expect(screen.getByText("22631")).toBeInTheDocument(); // OS build, only in the hardware endpoint
    await waitFor(() => expect(screen.getByText(/37\.4/)).toBeInTheDocument());
    expect(screen.getByText(/Insufficient historical data/i)).toBeInTheDocument();
  });

  it("shows the diagnostic instead of a fake value when a sensor is unavailable", async () => {
    getDevice.mockResolvedValue(DEVICE);
    getHardwareSnapshot.mockResolvedValue(HARDWARE);
    getCurrentMetrics.mockResolvedValue([
      {
        metricType: "CPU_UTILIZATION",
        instanceLabel: null,
        value: null,
        unit: null,
        availabilityStatus: "UNAVAILABLE",
        source: "OSHI/CentralProcessor",
        diagnostic: "Establishing CPU load baseline; utilization will be available on the next collection cycle.",
        timestamp: new Date().toISOString(),
      },
    ]);
    getMetricHistory.mockResolvedValue([]);

    render(<Overview />);

    await waitFor(() =>
      expect(screen.getByText(/Establishing CPU load baseline/i)).toBeInTheDocument(),
    );
    // Must never render a fabricated percentage when unavailable.
    expect(screen.queryByText(/^\d+\.\d%$/)).not.toBeInTheDocument();
  });
});
