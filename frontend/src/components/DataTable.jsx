import { useMemo, useState } from "react";
import { EmptyState } from "./States";
import "../styles/tables.css";

/**
 * Generic table. columns: [{ key, header, render?, align?, mono?, nowrap?, wrap?, minWidth?, sortValue?, sortFirst? }].
 *   nowrap     keep the cell on one line (short values: drive letters, rates, MACs)
 *   wrap       "words" (default text wrapping, break-word) | "anywhere" (ids with no break points)
 *   minWidth   CSS length applied to the column header, so a text column cannot be squeezed to a sliver
 *   sortValue  (row) => number | string | null. Makes the column sortable; null/undefined always sort last.
 *   sortFirst  "asc" | "desc": direction of the first click (default: desc for numbers, asc for text)
 * `defaultSort` ({ key, dir }) sets the initial order. `stack` turns the table into labelled blocks on narrow
 * screens. `caption` is announced to screen readers only.
 */

const isMissing = (v) => v === null || v === undefined || (typeof v === "number" && Number.isNaN(v));

function compare(a, b) {
  if (typeof a === "string" || typeof b === "string") {
    return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" });
  }
  return a - b;
}

function SortIcon({ dir }) {
  return (
    <svg className="sort-ind" viewBox="0 0 8 10" aria-hidden="true" fill="currentColor">
      {dir !== "desc" && <path d="M4 0 7.5 4h-7z" />}
      {dir !== "asc" && <path d="M4 10 .5 6h7z" />}
    </svg>
  );
}

export default function DataTable({ caption, columns, rows, getRowKey, emptyMessage, stack = false, defaultSort = null }) {
  const [sort, setSort] = useState(defaultSort);

  const ordered = useMemo(() => {
    const column = sort && columns.find((c) => c.key === sort.key);
    if (!column?.sortValue) return rows;
    const sign = sort.dir === "asc" ? 1 : -1;
    return rows
      .map((row, index) => ({ row, index, value: column.sortValue(row) }))
      .sort((a, b) => {
        if (isMissing(a.value) && isMissing(b.value)) return a.index - b.index;
        if (isMissing(a.value)) return 1;
        if (isMissing(b.value)) return -1;
        return compare(a.value, b.value) * sign || a.index - b.index;
      })
      .map((entry) => entry.row);
  }, [rows, columns, sort]);

  if (rows.length === 0) {
    return <EmptyState>{emptyMessage}</EmptyState>;
  }

  function toggle(column) {
    setSort((current) => {
      if (current?.key === column.key) return { key: column.key, dir: current.dir === "asc" ? "desc" : "asc" };
      const sample = rows.map(column.sortValue).find((v) => !isMissing(v));
      const first = column.sortFirst ?? (typeof sample === "string" ? "asc" : "desc");
      return { key: column.key, dir: first };
    });
  }

  return (
    <div className="table-scroll">
      <table className={stack ? "data-table data-table--stack" : "data-table"}>
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((c) => {
              const active = sort?.key === c.key && c.sortValue;
              return (
                <th
                  key={c.key}
                  scope="col"
                  aria-sort={active ? (sort.dir === "asc" ? "ascending" : "descending") : undefined}
                  className={[c.align === "right" ? "align-right" : "", c.nowrap ? "nowrap" : ""].filter(Boolean).join(" ") || undefined}
                  style={c.minWidth ? { minWidth: c.minWidth } : undefined}
                >
                  {c.sortValue ? (
                    <button type="button" className="th-sort" onClick={() => toggle(c)}>
                      {c.header}
                      <SortIcon dir={active ? sort.dir : null} />
                    </button>
                  ) : (
                    c.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {ordered.map((row) => (
            <tr key={getRowKey(row)}>
              {columns.map((c) => {
                const className =
                  [
                    c.align === "right" ? "align-right" : "",
                    c.mono ? "mono" : "",
                    c.nowrap ? "nowrap" : "",
                    c.wrap === "anywhere" ? "wrap-anywhere" : c.wrap === "words" ? "wrap-words" : "",
                  ]
                    .filter(Boolean)
                    .join(" ") || undefined;
                return (
                  <td key={c.key} className={className} data-label={c.header}>
                    {c.render ? c.render(row) : row[c.key]}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
