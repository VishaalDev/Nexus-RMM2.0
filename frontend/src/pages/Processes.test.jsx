import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import Processes from "./Processes";
import { PROCESSES } from "../test-fixtures-advisor";

vi.mock("../api/services/advisorService", () => ({
  getAdvisorReport: vi.fn(),
  getAlerts: vi.fn(),
  getProcesses: vi.fn(),
}));

import { getProcesses } from "../api/services/advisorService";

describe("Processes page", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows grouped applications with CPU and memory, and what the application is", async () => {
    getProcesses.mockResolvedValue(PROCESSES);
    render(<Processes />);
    const table = await screen.findByRole("table", { name: "Applications by CPU and memory use" });
    expect(within(table).getByText("chrome.exe")).toBeInTheDocument();
    expect(within(table).getByText(/× 40/)).toBeInTheDocument();
    expect(within(table).getByText(/Google Chrome \(all tabs\)\./)).toBeInTheDocument();
    expect(within(table).getByText("62.0%")).toBeInTheDocument();
    expect(within(table).getByText("mystery.exe")).toBeInTheDocument(); // from the memory ranking
    expect(screen.getByText("312")).toBeInTheDocument();
  });

  const appOrder = () => screen.getAllByRole("row").slice(1).map((r) => within(r).getByText(/\.exe$/).textContent);

  it("lists each application once and sorts by CPU first, then by any column on click", async () => {
    getProcesses.mockResolvedValue(PROCESSES);
    render(<Processes />);
    await screen.findByRole("table");
    expect(appOrder()).toEqual(["chrome.exe", "mystery.exe"]);
    expect(screen.getByRole("columnheader", { name: /CPU/ })).toHaveAttribute("aria-sort", "descending");

    fireEvent.click(screen.getByRole("button", { name: "Application" }));
    expect(appOrder()).toEqual(["chrome.exe", "mystery.exe"]); // A to Z
    fireEvent.click(screen.getByRole("button", { name: "Application" }));
    expect(appOrder()).toEqual(["mystery.exe", "chrome.exe"]); // Z to A
    expect(screen.getByRole("columnheader", { name: /Application/ })).toHaveAttribute("aria-sort", "descending");
  });

  it("filters applications by name or description", async () => {
    getProcesses.mockResolvedValue(PROCESSES);
    render(<Processes />);
    await screen.findByRole("table");
    fireEvent.change(screen.getByRole("searchbox", { name: "Filter applications" }), { target: { value: "mystery" } });
    expect(appOrder()).toEqual(["mystery.exe"]);
    fireEvent.change(screen.getByRole("searchbox", { name: "Filter applications" }), { target: { value: "zzz" } });
    expect(screen.getByText("No application matches the filter.")).toBeInTheDocument();
  });

  it("never shows 0% CPU before a baseline exists; it says CPU is still being measured", async () => {
    getProcesses.mockResolvedValue({ ...PROCESSES, cpuAvailable: false, totalCpuPercent: null, topByCpu: [], note: "Baseline captured." });
    render(<Processes />);
    await waitFor(() => expect(screen.getByText(/needs two samples to compare/)).toBeInTheDocument());
    expect(screen.getByText("Measuring…")).toBeInTheDocument();
  });

  it("explains why when process data is unavailable", async () => {
    getProcesses.mockResolvedValue({ available: false, note: "Process sampling is disabled (nexus.advisor.process-sampling-enabled=false).", topByCpu: [], topByMemory: [] });
    render(<Processes />);
    await waitFor(() => expect(screen.getByText(/Process data is unavailable\. Process sampling is disabled/)).toBeInTheDocument());
  });

  it("states the privacy boundary", async () => {
    getProcesses.mockResolvedValue(PROCESSES);
    render(<Processes />);
    expect(await screen.findByText(/command lines, user names and file paths are never collected/i)).toBeInTheDocument();
  });
});
