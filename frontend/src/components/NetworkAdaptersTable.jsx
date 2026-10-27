import DataTable from "./DataTable";
import StatusBadge from "./StatusBadge";
import RateCell from "./RateCell";
import { formatBitRate } from "../utils/format";
import { findReadingByInstance, readingsOfType } from "../utils/metrics";

/**
 * Static adapter specs (from Hardware DNA). When `current` (the live
 * metrics list) is supplied, RX/TX throughput columns are added, matched
 * to adapters by the label the backend uses for that instance. Live
 * readings that match no known adapter are still shown, never dropped.
 */
export default function NetworkAdaptersTable({ adapters, current }) {
  const live = current !== undefined;
  const rx = readingsOfType(current, "NETWORK_RX_BYTES_PER_SEC");
  const tx = readingsOfType(current, "NETWORK_TX_BYTES_PER_SEC");

  const rows = adapters.map((adapter) => ({
    key: adapter.name,
    label: adapter.displayName,
    adapter,
    rx: findReadingByInstance(rx, adapter.displayName, adapter.name),
    tx: findReadingByInstance(tx, adapter.displayName, adapter.name),
  }));

  let unlabeledNote = null;
  if (live) {
    const known = new Set(
      rows.flatMap((r) => [r.rx?.instanceLabel, r.tx?.instanceLabel]).filter(Boolean),
    );
    const extraLabels = [...new Set([...rx, ...tx].map((r) => r.instanceLabel).filter(Boolean))].filter(
      (label) => !known.has(label),
    );
    for (const label of extraLabels) {
      rows.push({
        key: `live|${label}`,
        label,
        adapter: null,
        rx: findReadingByInstance(rx, label),
        tx: findReadingByInstance(tx, label),
      });
    }
    unlabeledNote = [...rx, ...tx].find((r) => !r.instanceLabel)?.diagnostic ?? null;
  }

  const columns = [
    {
      key: "adapter",
      header: "Adapter",
      wrap: "words",
      minWidth: "13rem",
      render: (row) => (
        <div>
          {row.label}
          {row.adapter && row.adapter.name !== row.adapter.displayName && (
            <div className="note mono-id">{row.adapter.name}</div>
          )}
        </div>
      ),
    },
    {
      key: "status",
      header: "Status",
      nowrap: true,
      render: (row) =>
        row.adapter ? (
          <StatusBadge status={row.adapter.operStatus} />
        ) : (
          <span className="muted">Not in hardware inventory</span>
        ),
    },
    {
      key: "speed",
      header: "Link speed",
      nowrap: true,
      render: (row) => (row.adapter ? formatBitRate(row.adapter.speedBps) : "\u2014"),
    },
    { key: "mac", header: "MAC address", mono: true, nowrap: true, render: (row) => row.adapter?.macAddress ?? "\u2014" },
  ];

  if (live) {
    columns.push(
      {
        key: "rx",
        header: "Receive (RX)",
        minWidth: "7.5rem",
        render: (row) => <RateCell reading={row.rx} missingTitle={row.adapter?.operStatus === "UP" ? "No sample yet" : "Adapter is not up"} />,
      },
      {
        key: "tx",
        header: "Transmit (TX)",
        minWidth: "7.5rem",
        render: (row) => <RateCell reading={row.tx} missingTitle={row.adapter?.operStatus === "UP" ? "No sample yet" : "Adapter is not up"} />,
      },
    );
  }

  return (
    <>
      <DataTable
        caption="Network adapters"
        stack
        columns={columns}
        rows={rows}
        getRowKey={(row) => row.key}
        emptyMessage="No network adapters were reported by this system."
      />
      {unlabeledNote && <p className="panel-foot">{unlabeledNote}</p>}
    </>
  );
}
