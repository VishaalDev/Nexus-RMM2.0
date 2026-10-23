import { EmptyState } from "./States";
import "../styles/tables.css";

/**
 * Generic table. columns: [{ key, header, render?, align?, mono?, nowrap?, wrap?, minWidth? }].
 *   nowrap   keep the cell on one line (short values: drive letters, rates, MACs)
 *   wrap     "words" (default text wrapping, break-word) | "anywhere" (ids with no break points)
 *   minWidth CSS length applied to the column header, so a text column cannot be squeezed to a sliver
 * `stack` turns the table into labelled cards on narrow screens.
 * `caption` is announced to screen readers only.
 */
export default function DataTable({ caption, columns, rows, getRowKey, emptyMessage, stack = false }) {
  if (rows.length === 0) {
    return <EmptyState>{emptyMessage}</EmptyState>;
  }

  return (
    <div className="table-scroll">
      <table className={stack ? "data-table data-table--stack" : "data-table"}>
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                className={[c.align === "right" ? "align-right" : "", c.nowrap ? "nowrap" : ""].filter(Boolean).join(" ") || undefined}
                style={c.minWidth ? { minWidth: c.minWidth } : undefined}
              >
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
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
