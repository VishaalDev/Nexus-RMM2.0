import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, cleanup, within } from "@testing-library/react";
import Diagnostics from "./Diagnostics";

vi.mock("../api/services/diagnosticsService", () => ({ getDiagnostics: vi.fn() }));
vi.mock("../api/services/deviceService", () => ({ getBackendHealth: vi.fn(), getDevice: vi.fn() }));

import { getDiagnostics } from "../api/services/diagnosticsService";

const DIAG = {
  applicationStatus: "UP",
  computerId: "abc-123",
  hostname: "TEST-HOST",
  collectionIntervalSeconds: 10,
  databasePath: "P:\\Mini Project\\Main Project\\Nexus-RMM-Final\\nexus-rmm-final\\backend\\data\\nexus-rmm.db",
  totalStoredSamples: 7930,
  totalStoredEvents: 0,
  providers: [
    {
      metricTypes: ["NETWORK_RX_BYTES_PER_SEC", "NETWORK_TX_BYTES_PER_SEC"],
      supported: true,
      available: false,
      source: "OSHI/NetworkIF",
      diagnostic: "No operationally-up network adapter reported traffic.",
    },
    { metricTypes: ["GPU_UTILIZATION", "GPU_TEMPERATURE"], supported: false, available: false, source: "none", diagnostic: "Needs a vendor SDK." },
  ],
  eventMonitoring: { applicable: false, lastPollSuccess: false, lastPollAt: null, lastNewEventCount: 0, lastError: "Not Windows." },
};

describe("Diagnostics page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getDiagnostics.mockResolvedValue(DIAG);
  });
  afterEach(() => cleanup());

  it("lists each metric type on its own line instead of one long comma-joined string", async () => {
    render(<Diagnostics />);
    const rx = await screen.findByText("NETWORK_RX_BYTES_PER_SEC");
    const tx = screen.getByText("NETWORK_TX_BYTES_PER_SEC");
    expect(rx).not.toBe(tx);
    expect(rx.closest("td")).toBe(tx.closest("td"));
    expect(rx.closest("td")).toHaveClass("wrap-anywhere");
  });

  it("lets the capability matrix stack into labelled cards on narrow screens", async () => {
    render(<Diagnostics />);
    const table = (await screen.findByRole("table", { name: "Hardware provider status" }));
    expect(table).toHaveClass("data-table--stack");
    expect(within(table).getByText("OSHI/NetworkIF")).toHaveAttribute("data-label", "Source");
    expect(within(table).getByText(/No operationally-up network adapter/)).toHaveAttribute("data-label", "Diagnostic");
  });

  it("keeps every provider capability and diagnostic value", async () => {
    render(<Diagnostics />);
    expect(await screen.findByText("GPU_UTILIZATION")).toBeInTheDocument();
    expect(screen.getByText("Needs a vendor SDK.")).toBeInTheDocument();
    expect(screen.getByText(/nexus-rmm\.db/)).toBeInTheDocument();
  });
});
