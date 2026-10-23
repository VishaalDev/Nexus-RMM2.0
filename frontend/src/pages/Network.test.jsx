import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, cleanup, within } from "@testing-library/react";
import Network from "./Network";

vi.mock("../api/services/hardwareService", () => ({ getHardwareSnapshot: vi.fn() }));
vi.mock("../api/services/metricsService", () => ({ getCurrentMetrics: vi.fn(), getMetricHistory: vi.fn() }));

import { getHardwareSnapshot } from "../api/services/hardwareService";
import { getCurrentMetrics } from "../api/services/metricsService";

const NAME = "MediaTek Wi-Fi 6 MT7921 Wireless LAN Card";

describe("Network page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getHardwareSnapshot.mockResolvedValue({
      networkAdapters: [
        { name: "wlan0", displayName: NAME, macAddress: "AA:BB:CC:DD:EE:FF", speedBps: 866_700_000, operStatus: "UP" },
        { name: "eth1", displayName: "Remote NDIS Compatible Device", macAddress: "11:22:33:44:55:66", speedBps: 0, operStatus: "DOWN" },
      ],
    });
    getCurrentMetrics.mockResolvedValue([
      { metricType: "NETWORK_RX_BYTES_PER_SEC", instanceLabel: NAME, value: 2048, unit: "B/s", availabilityStatus: "AVAILABLE", timestamp: new Date().toISOString() },
      { metricType: "NETWORK_TX_BYTES_PER_SEC", instanceLabel: NAME, value: 1024, unit: "B/s", availabilityStatus: "AVAILABLE", timestamp: new Date().toISOString() },
    ]);
  });
  afterEach(() => cleanup());

  it("keeps the adapter name in a word-wrapping column with a minimum width, and its device id on a separate line", async () => {
    render(<Network />);
    const cell = (await screen.findByText(NAME)).closest("td");
    expect(cell).toHaveClass("wrap-words");
    expect(within(cell).getByText("wlan0")).toBeInTheDocument();
    const header = screen.getByRole("columnheader", { name: "Adapter" });
    expect(header.style.minWidth).toBe("13rem");
  });

  it("keeps status, link speed, MAC and rates on single lines", async () => {
    render(<Network />);
    const row = (await screen.findByText(NAME)).closest("tr");
    expect(within(row).getByText("Up").closest("td")).toHaveClass("nowrap");
    expect(within(row).getByText("AA:BB:CC:DD:EE:FF").closest("td")).toHaveClass("nowrap", "mono");
    expect(within(row).getByText(/Mbps|Gbps/).closest("td")).toHaveClass("nowrap");
    expect(within(row).getByText("2.0 KiB/s")).toBeInTheDocument();
    expect(within(row).getByText("1.0 KiB/s")).toBeInTheDocument();
  });

  it("does not invent throughput for an adapter that is down", async () => {
    render(<Network />);
    const row = (await screen.findByText("Remote NDIS Compatible Device")).closest("tr");
    expect(within(row).getAllByTitle("Adapter is not up").length).toBe(2);
    expect(within(row).queryByText(/KiB\/s|B\/s/)).not.toBeInTheDocument();
  });

  it("stacks into labelled cards on narrow screens", async () => {
    render(<Network />);
    const table = await screen.findByRole("table", { name: "Network adapters" });
    expect(table).toHaveClass("data-table--stack");
    expect(within(table).getByText("AA:BB:CC:DD:EE:FF")).toHaveAttribute("data-label", "MAC address");
  });
});
