import DataTable from "./DataTable";
import StatusBadge from "./StatusBadge";
import UtilizationBar from "./UtilizationBar";
import { formatTime, splitReadingValue } from "../utils/format";
import { METRIC_LABELS, USAGE_METRICS, usageLevel } from "../utils/metrics";

function ValueCell({ row }) {
  const parts = splitReadingValue(row.reading);
  if (!parts) return <span className="muted">N/A</span>;

  const isPercent = row.reading.unit === "%";
  const level = USAGE_METRICS.has(row.type) ? usageLevel(row.reading.value) : "neutral";

  return (
    <div className="value-cell">
      <span className="value-number">
        {parts.number}
        {parts.unit && <span className="value-unit">{parts.unit}</span>}
      </span>
      {isPercent && (
        <UtilizationBar
          value={row.reading.value}
          level={level}
          label={`${METRIC_LABELS[row.type]}${row.instance ? ` ${row.instance}` : ""}`}
        />
      )}
    </div>
  );
}

function StatusCell({ row }) {
  const { reading, type } = row;
  if (!reading) return <span className="muted">No reading yet</span>;

  const available = reading.availabilityStatus === "AVAILABLE";
  const level = available && USAGE_METRICS.has(type) ? usageLevel(reading.value) : null;
  const status = level === "high" || level === "elevated" ? level.toUpperCase() : reading.availabilityStatus;

  return (
    <div>
      <StatusBadge status={status} />
      {!available && reading.diagnostic && <div className="note">{reading.diagnostic}</div>}
    </div>
  );
}

const COLUMNS = [
  { key: "metric", header: "Metric", render: (row) => METRIC_LABELS[row.type] },
  {
    key: "instance",
    header: "Instance",
    render: (row) => row.instance ?? <span className="muted">&mdash;</span>,
  },
  { key: "value", header: "Value", render: (row) => <ValueCell row={row} /> },
  { key: "status", header: "Status", render: (row) => <StatusCell row={row} /> },
  {
    key: "source",
    header: "Source",
    render: (row) => <span className="muted">{row.reading?.source ?? "\u2014"}</span>,
  },
  {
    key: "updated",
    header: "Updated",
    mono: true,
    render: (row) => (row.reading ? formatTime(row.reading.timestamp) : "\u2014"),
  },
];

export default function LiveMetricsTable({ rows }) {
  return (
    <DataTable
      caption="Live hardware metrics"
      columns={COLUMNS}
      rows={rows}
      getRowKey={(row) => row.key}
      emptyMessage="No metrics reported yet."
    />
  );
}
