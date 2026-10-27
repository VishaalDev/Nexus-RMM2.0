import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import Advisor from "./Advisor";
import { AdvisorProvider } from "../hooks/AdvisorProvider";
import { ADVISOR_REPORT, ALERTS, QUIET_REPORT } from "../test-fixtures-advisor";

vi.mock("../api/services/advisorService", () => ({
  getAdvisorReport: vi.fn(),
  getAlerts: vi.fn(),
  getProcesses: vi.fn(),
}));

import { getAdvisorReport, getAlerts } from "../api/services/advisorService";

function renderPage() {
  return render(
    <AdvisorProvider>
      <Advisor />
    </AdvisorProvider>,
  );
}

describe("Advisor page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getAlerts.mockResolvedValue(ALERTS);
  });

  it("shows the headline, counts, top action and every issue with its steps", async () => {
    getAdvisorReport.mockResolvedValue(ADVISOR_REPORT);
    renderPage();

    await waitFor(() => expect(screen.getByText("1 critical issue and 1 warning need attention.")).toBeInTheDocument());
    const counts = screen.getByRole("list", { name: "Issue counts" });
    expect(within(counts).getByText(/critical/)).toBeInTheDocument();

    const top = screen.getByRole("heading", { name: "Do this first" }).closest("section");
    expect(within(top).getByText("Close tabs and windows you no longer need.")).toBeInTheDocument();

    const cpu = screen.getByRole("article", { name: "Critical: CPU is overloaded" });
    expect(within(cpu).getByText(/chrome\.exe \(40 processes\)/)).toBeInTheDocument();
    expect(within(cpu).getByText("Open Task Manager and sort by CPU.")).toBeInTheDocument();
    expect(within(cpu).getByText(/2 more steps/)).toBeInTheDocument();
    expect(within(cpu).getByText("Open for 30 min")).toBeInTheDocument();

    const heat = screen.getByRole("article", { name: "Warning: CPU is running hot" });
    expect(within(heat).getByText(/Power off and unplug/)).toBeInTheDocument();
  });

  it("hides housekeeping evidence keys but shows the real numbers", async () => {
    getAdvisorReport.mockResolvedValue(ADVISOR_REPORT);
    renderPage();
    const cpu = await screen.findByRole("article", { name: "Critical: CPU is overloaded" });
    expect(within(cpu).getByText("Top process")).toBeInTheDocument();
    expect(within(cpu).queryByText("Attribution note")).not.toBeInTheDocument();
  });

  it("lists recent alerts and the limitations", async () => {
    getAdvisorReport.mockResolvedValue(ADVISOR_REPORT);
    renderPage();
    const log = (await screen.findByRole("heading", { name: "Recent alerts" })).closest("section");
    await waitFor(() => expect(within(log).getByText("New")).toBeInTheDocument());
    expect(within(log).getByText(/Kept in memory/)).toBeInTheDocument();
    expect(screen.getByText("It never changes anything on your computer itself.")).toBeInTheDocument();
  });

  it("with no issues says nothing needs attention without claiming more than the report does", async () => {
    getAdvisorReport.mockResolvedValue(QUIET_REPORT);
    getAlerts.mockResolvedValue([]);
    renderPage();
    await waitFor(() => expect(screen.getByText(/Nothing needs attention right now/)).toBeInTheDocument());
    expect(await screen.findByText("No alerts since the backend started.")).toBeInTheDocument();
    expect(screen.getByText(/77% of the score could be assessed/)).toBeInTheDocument();
  });

  it("shows an actionable error when the report cannot be loaded", async () => {
    getAdvisorReport.mockRejectedValue(new Error("Failed to fetch"));
    renderPage();
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent(/Could not load advisor report/));
  });

  it("discloses when program-level detail is unavailable", async () => {
    getAdvisorReport.mockResolvedValue({ ...ADVISOR_REPORT, processInsight: false });
    renderPage();
    await waitFor(() => expect(screen.getByText("Unavailable")).toBeInTheDocument());
  });
});
