import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import App from "./App";

vi.mock("./api/services/deviceService", () => ({
  getBackendHealth: vi.fn(),
  getDevice: vi.fn(),
}));
vi.mock("./api/services/hardwareService", () => ({
  getHardwareSnapshot: vi.fn(),
}));
vi.mock("./api/services/metricsService", () => ({
  getCurrentMetrics: vi.fn(),
  getMetricHistory: vi.fn(),
}));
vi.mock("./api/services/diagnosticsService", () => ({ getDiagnostics: vi.fn() }));
vi.mock("./api/services/eventsService", () => ({ getEvents: vi.fn() }));
vi.mock("./api/services/advisorService", () => ({
  getAdvisorReport: vi.fn(),
  getAlerts: vi.fn(),
  getProcesses: vi.fn(),
}));

import { getBackendHealth, getDevice } from "./api/services/deviceService";
import { getHardwareSnapshot } from "./api/services/hardwareService";
import { getCurrentMetrics, getMetricHistory } from "./api/services/metricsService";
import { getAdvisorReport } from "./api/services/advisorService";
import { ADVISOR_REPORT } from "./test-fixtures-advisor";

describe("App", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = "";
  });

  it("shows an actionable message when the backend is unreachable, with no navigation shown", async () => {
    getBackendHealth.mockRejectedValue(new Error("Failed to fetch"));
    getDevice.mockRejectedValue(new Error("Failed to fetch"));
    getHardwareSnapshot.mockRejectedValue(new Error("Failed to fetch"));
    getCurrentMetrics.mockRejectedValue(new Error("Failed to fetch"));
    getMetricHistory.mockRejectedValue(new Error("Failed to fetch"));

    render(<App />);

    await waitFor(() => expect(screen.getByText(/Backend unavailable/i)).toBeInTheDocument());
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });

  it("shows the sidebar and the Overview page once the backend is reachable", async () => {
    getBackendHealth.mockResolvedValue({ status: "UP" });
    getDevice.mockResolvedValue({
      computerId: "abc-123",
      hostname: "TEST-HOST",
      manufacturer: "Dell Inc.",
      model: "OptiPlex 7090",
      osName: "Windows 11",
      osVersion: "10.0.22631",
    });
    getHardwareSnapshot.mockResolvedValue({ system: {} });
    getCurrentMetrics.mockResolvedValue([]);
    getMetricHistory.mockResolvedValue([]);

    render(<App />);

    await waitFor(() => expect(screen.getByRole("navigation")).toBeInTheDocument());
    expect(screen.getByRole("heading", { name: "Overview" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Storage" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sync" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Processes" })).toBeInTheDocument();
  });

  it("badges the Advisor entry with the number of issues needing attention", async () => {
    getBackendHealth.mockResolvedValue({ status: "UP" });
    getDevice.mockResolvedValue({ computerId: "abc-123", hostname: "TEST-HOST" });
    getHardwareSnapshot.mockResolvedValue({ system: {} });
    getCurrentMetrics.mockResolvedValue([]);
    getMetricHistory.mockResolvedValue([]);
    getAdvisorReport.mockResolvedValue(ADVISOR_REPORT);

    render(<App />);

    const advisor = await screen.findByRole("button", { name: /Advisor.*2 need attention/ });
    expect(advisor).toBeInTheDocument();
  });

  it("still works when the advisor endpoint fails (no badge, no crash)", async () => {
    getBackendHealth.mockResolvedValue({ status: "UP" });
    getDevice.mockResolvedValue({ computerId: "abc-123", hostname: "TEST-HOST" });
    getHardwareSnapshot.mockResolvedValue({ system: {} });
    getCurrentMetrics.mockResolvedValue([]);
    getMetricHistory.mockResolvedValue([]);
    getAdvisorReport.mockRejectedValue(new Error("down"));

    render(<App />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Advisor" })).toBeInTheDocument());
  });
});
