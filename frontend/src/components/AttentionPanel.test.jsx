import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import AttentionPanel from "./AttentionPanel";
import { AdvisorProvider } from "../hooks/AdvisorProvider";
import { ADVISOR_REPORT } from "../test-fixtures-advisor";

vi.mock("../api/services/advisorService", () => ({ getAdvisorReport: vi.fn(), getAlerts: vi.fn(), getProcesses: vi.fn() }));
import { getAdvisorReport } from "../api/services/advisorService";

describe("AttentionPanel", () => {
  it("renders nothing without a provider (never a misleading all-clear)", () => {
    const { container } = render(<AttentionPanel />);
    expect(container).toBeEmptyDOMElement();
  });

  it("lists urgent issues but not informational notes, and links to the Advisor", async () => {
    getAdvisorReport.mockResolvedValue(ADVISOR_REPORT);
    render(
      <AdvisorProvider>
        <AttentionPanel />
      </AdvisorProvider>,
    );
    await waitFor(() => expect(screen.getByText("CPU is overloaded")).toBeInTheDocument());
    expect(screen.getByText("CPU is running hot")).toBeInTheDocument();
    expect(screen.queryByText("Some areas cannot be assessed")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open Advisor" })).toHaveAttribute("href", "#/advisor");
  });

  it("renders nothing when the advisor request fails", async () => {
    getAdvisorReport.mockRejectedValue(new Error("down"));
    const { container } = render(
      <AdvisorProvider>
        <AttentionPanel />
      </AdvisorProvider>,
    );
    await new Promise((r) => setTimeout(r, 20));
    expect(container).toBeEmptyDOMElement();
  });
});
