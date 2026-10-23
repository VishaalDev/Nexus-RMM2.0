import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup, within } from "@testing-library/react";
import Risk from "./Risk";

vi.mock("../api/services/healthService", () => ({
  getHealthReport: vi.fn(),
  getRiskReport: vi.fn(),
}));

import { getRiskReport } from "../api/services/healthService";

/** Shaped like the real /api/risk response for the situation in the bug report. */
const RISK = {
  generatedAt: "2026-10-21T12:00:00Z",
  overallRiskScore: 46,
  overallLevel: "HIGH",
  confidence: "LOW",
  methodology: "Heuristic severity index (0-100), not a probability of failure. NOT_ASSESSED means no usable evidence, not low risk.",
  components: [
    {
      component: "THERMAL",
      label: "Thermal",
      riskScore: 57,
      level: "HIGH",
      confidence: "LOW",
      factors: [
        {
          code: "SUSTAINED_HEAT",
          description: "CPU was above the warning temperature for 76.7% of the window.",
          severity: 0.57,
          evidence: { fractionPercent: 76.7 },
        },
      ],
      notAssessedReason: null,
    },
    { component: "CPU", label: "CPU", riskScore: 0, level: "LOW", confidence: "LOW", factors: [], notAssessedReason: null },
    { component: "MEMORY", label: "Memory", riskScore: 0, level: "LOW", confidence: "HIGH", factors: [], notAssessedReason: null },
    {
      component: "STORAGE_CAPACITY",
      label: "Storage capacity",
      riskScore: 80,
      level: "CRITICAL",
      confidence: "MEDIUM",
      horizonDays: 5.04,
      factors: [
        {
          code: "STORAGE_FILLING",
          description: "C:\\ is projected to fill in about 5.0 days at its recent growth rate.",
          severity: 0.8,
          evidence: { instance: "C:\\", daysToFull: 4.999 },
        },
      ],
      notAssessedReason: null,
    },
    {
      component: "NETWORK",
      label: "Network",
      level: "NOT_ASSESSED",
      confidence: "LOW",
      factors: [],
      notAssessedReason: "Activity anomalies are not failure indicators; no failure risk is derived from this component.",
    },
  ],
};

const card = (name) => screen.findByRole("article", { name });

describe("Risk page", () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => cleanup());

  it("states that the overall score is a heuristic severity index, not a probability of hardware failure", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    expect(await screen.findByText(/not a probability of hardware failure/)).toBeInTheDocument();
    expect(screen.getByText("not a probability of failure")).toBeInTheDocument(); // page header
  });

  it("shows the overall score and level AND makes evidence confidence prominent", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    await waitFor(() => expect(screen.getByText("46")).toBeInTheDocument());
    const panel = screen.getByLabelText("Overall evidence confidence");
    expect(within(panel).getByText("Low")).toBeInTheDocument();
    expect(within(panel).getByText(/short, sparse or low-coverage history/i)).toBeInTheDocument();
    expect(screen.getByText(/Low evidence confidence: this overall score rests on limited history/)).toBeInTheDocument();
  });

  it("explains that unassessed or limited-evidence results cannot establish safety", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    const notes = await screen.findAllByRole("note");
    const callout = notes.find((n) => /cannot establish that the system is safe/.test(n.textContent));
    expect(callout).toBeTruthy();
    expect(callout.textContent).toMatch(/Not assessed.*limited-evidence/s);
    expect(callout.textContent).toMatch(/Low risk.*Medium or High evidence confidence/s);
  });

  it("does NOT show a Low-risk/zero component with Low evidence confidence as 'Low risk'", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    const cpu = await card("CPU risk");
    expect(within(cpu).getByText(/No indicators/)).toBeInTheDocument();
    expect(within(cpu).queryByText("Low risk")).not.toBeInTheDocument();
    expect(within(cpu).getByText(/not .known to be safe./)).toBeInTheDocument();
    expect(within(cpu).getByRole("meter")).toHaveAttribute("aria-valuenow", "0"); // the real score is preserved, not hidden
  });

  it("keeps a genuine assessed Low risk (Medium/High confidence) distinguishable", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    const memory = await card("Memory risk");
    expect(within(memory).getByText("Low risk")).toBeInTheDocument();
    expect(within(memory).queryByText(/No indicators/)).not.toBeInTheDocument();
    expect(within(memory).queryByText(/known to be safe/)).not.toBeInTheDocument();
    expect(within(memory).getByText("High")).toBeInTheDocument(); // evidence confidence
  });

  it("shows Not assessed with the backend's own reason, and no score", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    const net = await card("Network risk");
    expect(within(net).getByText("Not assessed")).toBeInTheDocument();
    expect(within(net).getByText(/not failure indicators/)).toBeInTheDocument();
    expect(within(net).getByRole("meter")).not.toHaveAttribute("aria-valuenow");
    expect(within(net).queryByText(/Evidence confidence/)).not.toBeInTheDocument();
  });

  it("labels a factor's percentage as SEVERITY (0-100), not as a probability or a share of samples", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    const thermal = await card("Thermal risk");
    const item = within(thermal).getByText(/above the warning temperature for 76\.7%/).closest("li");
    expect(within(item).getByText("Severity")).toBeInTheDocument();
    expect(within(item).getByText("57")).toBeInTheDocument();
    expect(within(item).getByText("/100")).toBeInTheDocument();
    expect(item.textContent).not.toMatch(/57%/);
  });

  it("explains the measured percentage separately, using the backend evidence", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    const thermal = await card("Thermal risk");
    expect(within(thermal).getByText("Samples above the warning temperature")).toBeInTheDocument();
    expect(within(thermal).getByText("76.7%")).toBeInTheDocument();
  });

  it("preserves every score, description, horizon, evidence value and reason from the backend", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    const thermal = await card("Thermal risk");
    expect(within(thermal).getByRole("meter")).toHaveAttribute("aria-valuenow", "57");
    expect(within(thermal).getByText(/High risk/)).toBeInTheDocument();
    const storage = await card("Storage capacity risk");
    expect(within(storage).getByText(/Projected time to event: about 5\.0 days/)).toBeInTheDocument();
    expect(within(storage).getByText(/projected to fill in about 5\.0 days/)).toBeInTheDocument();
    expect(within(storage).getByText("C:\\")).toBeInTheDocument(); // evidence: instance
    expect(within(storage).getByText("5 days")).toBeInTheDocument(); // evidence: daysToFull 4.999 -> 5
    expect(within(storage).getByText("Critical risk")).toBeInTheDocument();
  });

  it("includes a glossary of the three ideas people confuse", async () => {
    getRiskReport.mockResolvedValue(RISK);
    render(<Risk />);
    expect(await screen.findByText("Factor severity (0\u2013100)")).toBeInTheDocument();
    expect(screen.getByText("Evidence confidence", { selector: "dt" })).toBeInTheDocument();
    expect(screen.getByText(/Low risk vs\. limited evidence vs\. not assessed/)).toBeInTheDocument();
  });

  it("shows an actionable error when risk cannot be loaded", async () => {
    getRiskReport.mockRejectedValue(new Error("Request failed (500)"));
    render(<Risk />);
    await waitFor(() => expect(screen.getByText(/Request failed \(500\)/)).toBeInTheDocument());
  });

  it("never renders a green Low risk for an overall Low result with Low confidence", async () => {
    getRiskReport.mockResolvedValue({ ...RISK, overallRiskScore: 0, overallLevel: "LOW", confidence: "LOW" });
    render(<Risk />);
    const panel = (await screen.findByText("Overall risk")).closest("section");
    expect(within(panel).getByText(/No indicators/)).toBeInTheDocument();
    expect(within(panel).queryByText("Low risk")).not.toBeInTheDocument();
  });
});
