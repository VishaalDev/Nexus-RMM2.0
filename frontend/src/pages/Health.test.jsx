import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup, within } from "@testing-library/react";
import Health from "./Health";

vi.mock("../api/services/healthService", () => ({
  getHealthReport: vi.fn(),
  getRiskReport: vi.fn(),
}));

import { getHealthReport } from "../api/services/healthService";

const REPORT = {
  generatedAt: "2026-10-21T12:00:00Z",
  computerId: "abc-123",
  overallScore: 56,
  overallStatus: "DEGRADED",
  dataCoverage: 0.657,
  overallBasis: "Weighted mean of 5 of 8 components, capped at lowest component score + 30.",
  components: [
    {
      component: "CPU",
      label: "CPU",
      score: 100,
      status: "HEALTHY",
      weight: 1,
      sampleCount: 49,
      dataCoverage: 0.14,
      basis: "CPU utilization over the last 60 min.",
      instances: [],
      findings: [],
      noDataReason: null,
    },
    {
      component: "THERMAL",
      label: "Thermal",
      score: 26,
      status: "CRITICAL",
      weight: 1.5,
      sampleCount: 49,
      dataCoverage: 0.14,
      basis: "CPU temperature, 95th percentile.",
      instances: [],
      findings: [
        { severity: "INFO", code: "THERMAL_RISING_TREND", message: "Baseline rising.", evidence: {} },
        { severity: "CRITICAL", code: "THERMAL_CRITICAL", message: "CPU temperature 95th percentile is 95.1 degrees.", evidence: {} },
      ],
      noDataReason: null,
    },
    {
      component: "STORAGE_CAPACITY",
      label: "Storage capacity",
      score: 82,
      status: "FAIR",
      weight: 1.5,
      sampleCount: 98,
      dataCoverage: 0.2,
      basis: "Current utilization of each volume.",
      instances: [
        { instance: "C:\\", score: 72, status: "FAIR", sampleCount: 49, dataCoverage: 0.1, metrics: {}, findings: [], noDataReason: null },
        { instance: "E:\\", score: null, status: "NO_DATA", sampleCount: 0, dataCoverage: 0, metrics: {}, findings: [], noDataReason: "gone" },
      ],
      findings: [],
      noDataReason: null,
    },
    {
      component: "BATTERY",
      label: "Battery",
      score: null,
      status: "NO_DATA",
      weight: 0.75,
      sampleCount: 0,
      dataCoverage: 0,
      basis: "Battery charge-level usage pattern only.",
      instances: [],
      findings: [],
      noDataReason: "Not applicable: this device reports no battery.",
    },
  ],
  limitations: ["GPU health is not assessed.", "Scores are heuristics, not a diagnosis."],
};

describe("Health page", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it("shows the overall score with its basis", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    await waitFor(() => expect(screen.getByText("56")).toBeInTheDocument());
    expect(screen.getByText(/capped at lowest component score/)).toBeInTheDocument();
  });

  it("keeps four different coverage ideas apart and labelled", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    const grid = await screen.findByLabelText("Coverage of this score");

    const cell = (title) => within(grid).getByText(title).closest("div");
    expect(within(cell("Assessed components")).getByText("3 of 4")).toBeInTheDocument();
    expect(within(cell("Component-weight coverage")).getByText("66%")).toBeInTheDocument();
    expect(within(cell("Component-weight coverage")).getByText(/total weight that had data/)).toBeInTheDocument();
    // mean of the three scored components' telemetry coverage: (0.14 + 0.14 + 0.2) / 3 = 0.16
    expect(within(cell("Telemetry coverage")).getByText("16%")).toBeInTheDocument();
    expect(within(cell("Telemetry coverage")).getByText(/valid samples in the last hour versus the number expected/)).toBeInTheDocument();
    expect(within(cell("Insufficient data")).getByText("1 component")).toBeInTheDocument();
    expect(within(cell("Insufficient data")).getByText(/Battery: not scored, excluded from the overall score, never counted as healthy/)).toBeInTheDocument();
  });

  it("warns that the overall score reflects only the assessed components", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    expect(await screen.findByText(/reflects only the 3 assessed components/)).toBeInTheDocument();
  });

  it("does not show the caution when every component was assessed", async () => {
    getHealthReport.mockResolvedValue({ ...REPORT, components: REPORT.components.filter((c) => c.status !== "NO_DATA") });
    render(<Health />);
    await screen.findByLabelText("Coverage of this score");
    expect(screen.queryByText(/reflects only the/)).not.toBeInTheDocument();
    expect(screen.getByText("Every component produced a score.")).toBeInTheDocument();
  });

  it("labels per-component coverage as telemetry coverage", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    const cpu = await screen.findByRole("article", { name: "CPU" });
    expect(within(cpu).getByText(/Telemetry coverage 14%/)).toBeInTheDocument();
  });

  it("renders a No-data component with its reason and never a score", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    const card = await screen.findByRole("article", { name: "Battery" });
    expect(within(card).getByText("No data")).toBeInTheDocument();
    expect(within(card).getByText(/Insufficient data/)).toBeInTheDocument();
    expect(within(card).queryByText("Healthy")).not.toBeInTheDocument();
    expect(within(card).getByText(/reports no battery/)).toBeInTheDocument();
    expect(within(card).getByText("\u2014")).toBeInTheDocument();
    expect(within(card).getByRole("meter")).not.toHaveAttribute("aria-valuenow");
  });

  it("lists findings most severe first with the severity as text", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    const card = await screen.findByRole("article", { name: "Thermal" });
    const items = within(card).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("Critical");
    expect(items[0]).toHaveTextContent("95.1");
    expect(items[1]).toHaveTextContent("Info");
  });

  it("shows scored instances (labels with backslashes intact) and names the unassessed ones", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    const card = await screen.findByRole("article", { name: "Storage capacity" });
    expect(within(card).getByText("C:\\")).toBeInTheDocument();
    expect(within(card).getByText(/Not assessed: E:\\/)).toBeInTheDocument();
  });

  it("states what the report cannot see", async () => {
    getHealthReport.mockResolvedValue(REPORT);
    render(<Health />);
    expect(await screen.findByText("GPU health is not assessed.")).toBeInTheDocument();
  });

  it("shows an actionable error when the analysis cannot be loaded", async () => {
    getHealthReport.mockRejectedValue(new Error("Request failed (500)"));
    render(<Health />);
    await waitFor(() => expect(screen.getByText(/Request failed \(500\)/)).toBeInTheDocument());
    expect(screen.queryByText("56")).not.toBeInTheDocument();
  });

  it("renders an overall No-data report without inventing a score", async () => {
    getHealthReport.mockResolvedValue({
      ...REPORT,
      overallScore: null,
      overallStatus: "NO_DATA",
      dataCoverage: 0,
      overallBasis: "No component had enough data to be scored.",
      components: REPORT.components.filter((c) => c.component === "BATTERY"),
    });
    render(<Health />);
    const grid = await screen.findByLabelText("Coverage of this score");
    expect(within(grid).getByText("0 of 1")).toBeInTheDocument();
    expect(screen.getByText("No component had enough data to be scored.")).toBeInTheDocument();
    expect(screen.queryByText("Healthy")).not.toBeInTheDocument();
  });
});
