import { describe, it, expect, afterEach } from "vitest";
import { render, screen, cleanup, fireEvent, within } from "@testing-library/react";
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

  describe("sorting", () => {
    const cols = [
      { key: "n", header: "Name", sortValue: (r) => r.n, render: (r) => r.n },
      { key: "v", header: "Value", sortValue: (r) => r.v, render: (r) => r.v ?? "none" },
    ];
    const data = [
      { n: "b", v: 2 },
      { n: "a", v: null },
      { n: "c", v: 9 },
    ];
    const names = () => screen.getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell")[0].textContent);
    const table = (extra = {}) => (
      <DataTable caption="t" columns={cols} rows={data} getRowKey={(r) => r.n} emptyMessage="none" {...extra} />
    );

    it("keeps the given order until a sortable header is used", () => {
      render(table());
      expect(names()).toEqual(["b", "a", "c"]);
    });

    it("sorts numbers high-to-low on the first click, then flips, and always puts missing values last", () => {
      render(table());
      fireEvent.click(screen.getByRole("button", { name: "Value" }));
      expect(names()).toEqual(["c", "b", "a"]);
      expect(screen.getByRole("columnheader", { name: "Value" })).toHaveAttribute("aria-sort", "descending");
      fireEvent.click(screen.getByRole("button", { name: "Value" }));
      expect(names()).toEqual(["b", "c", "a"]); // ascending; the missing value is still last
    });

    it("sorts text A-to-Z first and honours defaultSort", () => {
      render(table({ defaultSort: { key: "n", dir: "desc" } }));
      expect(names()).toEqual(["c", "b", "a"]);
      fireEvent.click(screen.getByRole("button", { name: "Name" }));
      expect(names()).toEqual(["a", "b", "c"]);
    });
  });
});
