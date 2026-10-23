import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";

// jsdom has no layout, so recharts would render nothing. Replace it with
// stand-ins that expose exactly what HistoryChart hands to the chart.
vi.mock("recharts", () => ({
  ResponsiveContainer: ({ children }) => <div data-testid="rc">{children}</div>,
  LineChart: ({ data, margin, children }) => (
    <div data-testid="chart" data-points={JSON.stringify(data)} data-margin={JSON.stringify(margin)}>
      {children}
    </div>
  ),
  Line: ({ connectNulls }) => <div data-testid="line" data-connect-nulls={String(connectNulls)} />,
  CartesianGrid: () => null,
  XAxis: () => null,
  YAxis: () => null,
  Tooltip: () => null,
}));

import HistoryChart from "./HistoryChart";

const T0 = Date.parse("2026-10-21T10:00:00Z");
const pts = (values) => values.map((value, i) => ({ timestamp: new Date(T0 + i * 10_000).toISOString(), value }));

describe("HistoryChart", () => {
  afterEach(() => cleanup());

  it("keeps missing samples as null gaps - never as zero - and does not bridge them", () => {
    render(<HistoryChart points={pts([10, null, 30, null, 50])} unit="%" />);
    const data = JSON.parse(screen.getByTestId("chart").dataset.points);
    expect(data.map((d) => d.value)).toEqual([10, null, 30, null, 50]);
    expect(data.some((d) => d.value === 0)).toBe(false);
    expect(screen.getByTestId("line").dataset.connectNulls).toBe("false");
    expect(screen.getByText(/2 samples in this window had no reading/)).toBeInTheDocument();
  });

  it("reserves right-hand margin so the last axis label is not clipped", () => {
    render(<HistoryChart points={pts([1, 2, 3])} unit="%" />);
    const margin = JSON.parse(screen.getByTestId("chart").dataset.margin);
    expect(margin.right).toBeGreaterThanOrEqual(24);
  });

  it("shows an empty state, not a flat line, when fewer than two real readings exist", () => {
    render(<HistoryChart points={pts([null, 5, null])} unit="%" />);
    expect(screen.queryByTestId("chart")).not.toBeInTheDocument();
    expect(screen.getByText(/Insufficient historical data/)).toBeInTheDocument();
  });
});
