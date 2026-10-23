import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup } from "@testing-library/react";
import DataTable from "./DataTable";

const columns = [
  { key: "name", header: "Name", wrap: "words", minWidth: "9rem", render: (r) => r.name },
  { key: "id", header: "Device id", mono: true, wrap: "anywhere", render: (r) => r.id },
  { key: "rate", header: "Rate", align: "right", nowrap: true, render: (r) => r.rate },
];
const rows = [{ name: "Local Disk", id: "\\\\.\\PHYSICALDRIVE0", rate: "1.2 MiB/s" }];

describe("DataTable", () => {
  afterEach(() => cleanup());

  it("labels every cell with its column header so stacked cards stay readable", () => {
    render(<DataTable caption="t" columns={columns} rows={rows} getRowKey={(r) => r.id} emptyMessage="none" stack />);
    expect(screen.getByText("Local Disk")).toHaveAttribute("data-label", "Name");
    expect(screen.getByText("1.2 MiB/s")).toHaveAttribute("data-label", "Rate");
    expect(screen.getByRole("table")).toHaveClass("data-table--stack");
  });

  it("applies per-column wrapping options and nothing else by default", () => {
    render(<DataTable caption="t" columns={columns} rows={rows} getRowKey={(r) => r.id} emptyMessage="none" />);
    expect(screen.getByRole("table")).not.toHaveClass("data-table--stack");
    expect(screen.getByText("Local Disk")).toHaveClass("wrap-words");
    expect(screen.getByText("\\\\.\\PHYSICALDRIVE0")).toHaveClass("wrap-anywhere", "mono");
    expect(screen.getByText("1.2 MiB/s")).toHaveClass("nowrap", "align-right");
    expect(screen.getByRole("columnheader", { name: "Name" }).style.minWidth).toBe("9rem");
  });

  it("still renders the empty message when there are no rows", () => {
    render(<DataTable caption="t" columns={columns} rows={[]} getRowKey={(r) => r.id} emptyMessage="Nothing here" />);
    expect(screen.getByText("Nothing here")).toBeInTheDocument();
  });
});
