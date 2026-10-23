import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, cleanup, waitFor, within, fireEvent } from "@testing-library/react";
import Sync from "./Sync";

vi.mock("../api/services/syncService", () => ({ getSyncStatus: vi.fn(), triggerSync: vi.fn() }));

import { getSyncStatus, triggerSync } from "../api/services/syncService";

const BASE = {
  state: "UP_TO_DATE",
  running: false,
  configuration: {
    configured: true,
    destinationSet: true,
    destination: "https://sync.example.org/api/ingest",
    authConfigured: true,
    warnings: [],
    batchSize: 200,
    pollIntervalSeconds: 10,
  },
  connectivity: "REACHABLE",
  queue: {
    pending: 3,
    synced: 120,
    failed: 1,
    total: 124,
    byType: {
      METRIC_SAMPLE: { pending: 3, synced: 118, failed: 0 },
      HARDWARE_EVENT: { pending: 0, synced: 2, failed: 1 },
    },
  },
  lastAttemptAt: "2026-10-22T10:00:00Z",
  lastAttemptResult: "SUCCESS",
  lastSuccessfulSyncAt: "2026-10-22T10:00:00Z",
  consecutiveFailures: 0,
};

const NOT_CONFIGURED = {
  state: "NOT_CONFIGURED",
  running: false,
  configuration: { configured: false, destinationSet: false, authConfigured: false, warnings: [], batchSize: 200, pollIntervalSeconds: 10 },
  connectivity: "NOT_CONFIGURED",
  queue: { pending: 42, synced: 0, failed: 0, total: 42, byType: { METRIC_SAMPLE: { pending: 42, synced: 0, failed: 0 } } },
  consecutiveFailures: 0,
  nextStep: "No synchronization destination is configured, so nothing is sent anywhere. Set nexus.sync.destination-url ...",
};

describe("Sync page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });
  afterEach(() => cleanup());

  it("explains missing configuration, never claims success, and disables Sync Now", async () => {
    getSyncStatus.mockResolvedValue(NOT_CONFIGURED);
    render(<Sync />);

    expect(await screen.findByText(/No synchronization destination is configured/)).toBeInTheDocument();
    expect(screen.getAllByText("Not configured").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "Sync Now" })).toBeDisabled();
    expect(screen.getByText(/NEXUS_SYNC_DESTINATION_URL/)).toBeInTheDocument();
    expect(screen.getByText("Never")).toBeInTheDocument(); // last successful synchronization
    expect(triggerSync).not.toHaveBeenCalled();
  });

  it("shows pending, synchronized and failed counts exactly as the backend reports them", async () => {
    getSyncStatus.mockResolvedValue(BASE);
    render(<Sync />);

    await screen.findByText("Pending (stored locally, not yet acknowledged)");
    const pending = screen.getByText("Pending (stored locally, not yet acknowledged)").previousSibling;
    const synced = screen.getByText("Synchronized (acknowledged by destination)").previousSibling;
    const failed = screen.getByText("Failed (permanently rejected by destination)").previousSibling;
    expect(pending).toHaveTextContent("3");
    expect(synced).toHaveTextContent("120");
    expect(failed).toHaveTextContent("1");

    const table = screen.getByRole("table", { name: "Queue by data type" });
    expect(within(table).getByText("Metric samples")).toBeInTheDocument();
    expect(within(table).getByText("Hardware events")).toBeInTheDocument();
  });

  it("never renders the auth token, only whether one is configured", async () => {
    getSyncStatus.mockResolvedValue(BASE);
    const { container } = render(<Sync />);
    await screen.findByText(/Auth token configured \(value hidden\)/);
    expect(container.textContent).not.toMatch(/secret|Bearer/i);
  });

  it("Sync Now calls the real trigger, then refreshes status from the API", async () => {
    getSyncStatus.mockResolvedValue(BASE);
    triggerSync.mockResolvedValue({ outcome: "STARTED", message: "Synchronization started.", status: BASE });
    render(<Sync />);

    const button = await screen.findByRole("button", { name: "Sync Now" });
    expect(button).toBeEnabled();
    const callsBefore = getSyncStatus.mock.calls.length;
    fireEvent.click(button);

    expect(triggerSync).toHaveBeenCalledTimes(1);
    expect(await screen.findByText("Synchronization started.")).toBeInTheDocument();
    await waitFor(() => expect(getSyncStatus.mock.calls.length).toBeGreaterThan(callsBefore));
  });

  it("disables Sync Now while the backend reports a run in progress", async () => {
    getSyncStatus.mockResolvedValue({ ...BASE, state: "SYNCING", running: true });
    render(<Sync />);
    const button = await screen.findByRole("button", { name: /Syncing/ });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");
  });

  it("shows the backend's error when the trigger is refused and does not claim success", async () => {
    getSyncStatus.mockResolvedValue(BASE);
    triggerSync.mockRejectedValue(new Error("A synchronization is already in progress."));
    render(<Sync />);

    fireEvent.click(await screen.findByRole("button", { name: "Sync Now" }));
    const alert = await screen.findByText(/Could not start synchronization/);
    expect(alert).toHaveTextContent("A synchronization is already in progress.");
    expect(screen.queryByText("Synchronization started.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sync Now" })).toBeEnabled();
  });

  it("shows the last error with actionable next steps after a real failure", async () => {
    getSyncStatus.mockResolvedValue({
      ...BASE,
      state: "RETRYING",
      connectivity: "UNREACHABLE",
      lastAttemptResult: "FAILED",
      consecutiveFailures: 3,
      nextAttemptAt: "2026-10-22T10:05:00Z",
      lastSuccessfulSyncAt: undefined,
      nextStep: "The destination could not be reached. Check that the server is running ...",
      lastError: {
        category: "CONNECTION_FAILED",
        message: "Could not connect to https://sync.example.org/api/ingest",
        at: "2026-10-22T10:00:00Z",
        nextStep: "The destination could not be reached.",
      },
    });
    render(<Sync />);

    expect(await screen.findByText("Could not connect to https://sync.example.org/api/ingest")).toBeInTheDocument();
    expect(screen.getByText(/Last error: connection failed/)).toBeInTheDocument();
    expect(screen.getByText(/Check that the server is running/)).toBeInTheDocument();
    expect(screen.getAllByText("Unreachable").length).toBeGreaterThan(0);
    const failuresRow = screen.getByText("Consecutive failures").closest("tr");
    expect(within(failuresRow).getByText("3")).toBeInTheDocument();
  });

  it("shows invalid configuration problems and warnings", async () => {
    getSyncStatus.mockResolvedValue({
      ...NOT_CONFIGURED,
      state: "CONFIG_INVALID",
      configuration: { ...NOT_CONFIGURED.configuration, destinationSet: true, issue: "nexus.sync.destination-url must start with http:// or https://", warnings: ["Use https:// for any real destination."] },
      nextStep: "Fix the synchronization configuration and restart the backend",
    });
    render(<Sync />);
    expect(await screen.findByText(/must start with http:\/\/ or https:\/\//)).toBeInTheDocument();
    expect(screen.getByText("Use https:// for any real destination.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sync Now" })).toBeDisabled();
  });

  it("shows an actionable error when the status request itself fails", async () => {
    getSyncStatus.mockRejectedValue(new Error("Failed to fetch"));
    render(<Sync />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/Could not load synchronization status/);
  });
});
