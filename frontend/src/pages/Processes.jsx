import { useMemo, useState } from "react";
import DataBoundary from "../components/DataBoundary";
import DataTable from "../components/DataTable";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import StatStrip from "../components/StatStrip";
import UtilizationBar from "../components/UtilizationBar";
import { EmptyState } from "../components/States";
import { usePolling } from "../hooks/usePolling";
import { getProcesses } from "../api/services/advisorService";
import { PROCESS_POLL_MS } from "../hooks/useAdvisorData";
import { formatBytes, formatTime } from "../utils/format";
import { formatUptime } from "../utils/advisor";
import "../styles/advisor.css";

const pct = (v) => (v === null || v === undefined ? "N/A" : `${v.toFixed(1)}%`);

/** The backend ranks two lists (by CPU, by memory). One table shows their union, each application once. */
function mergeGroups(snap) {
  const byName = new Map();
  for (const g of [...(snap.topByCpu ?? []), ...(snap.topByMemory ?? [])]) {
    if (!byName.has(g.name)) byName.set(g.name, g);
  }
  return [...byName.values()];
}

function AppName({ group }) {
  return (
    <div>
      <div>
        <span className="proc-name">{group.name}</span>
        {group.processCount > 1 && <span className="muted"> &times; {group.processCount}</span>}
      </div>
      {(group.description || group.advice) && (
        <div className="proc-sub">
          {group.description}
          {group.advice && (
            <details className="disclosure proc-tip">
              <summary>Tip</summary>
              <p>{group.advice}</p>
            </details>
          )}
        </div>
      )}
    </div>
  );
}

function buildColumns(cpuAvailable) {
  return [
    {
      key: "name",
      header: "Application",
      sortValue: (g) => g.name,
      render: (g) => <AppName group={g} />,
      wrap: "words",
      minWidth: "16rem",
    },
    {
      key: "cpu",
      header: "CPU",
      sortValue: (g) => (cpuAvailable ? (g.cpuPercent ?? null) : null),
      render: (g) =>
        cpuAvailable ? (
          <div className="proc-meter">
            <span className="value-number">{pct(g.cpuPercent)}</span>
            <UtilizationBar value={g.cpuPercent ?? 0} level="normal" label={`${g.name} CPU`} />
          </div>
        ) : (
          <span className="muted">&mdash;</span>
        ),
      nowrap: true,
    },
    {
      key: "mem",
      header: "Memory",
      sortValue: (g) => g.memoryBytes ?? null,
      render: (g) => (
        <div className="proc-meter">
          <span className="value-number">{formatBytes(g.memoryBytes)}</span>
          <UtilizationBar value={g.memoryPercent ?? 0} level="normal" label={`${g.name} memory`} />
          <span className="muted num proc-pct">{pct(g.memoryPercent)}</span>
        </div>
      ),
      nowrap: true,
    },
    {
      key: "count",
      header: "Processes",
      sortValue: (g) => g.processCount ?? null,
      render: (g) => g.processCount,
      align: "right",
      nowrap: true,
    },
  ];
}

function ProcessTable({ snap }) {
  const [query, setQuery] = useState("");
  const rows = useMemo(() => mergeGroups(snap), [snap]);
  const needle = query.trim().toLowerCase();
  const shown = needle
    ? rows.filter((g) => g.name.toLowerCase().includes(needle) || (g.description ?? "").toLowerCase().includes(needle))
    : rows;
  const columns = useMemo(() => buildColumns(snap.cpuAvailable), [snap.cpuAvailable]);

  return (
    <Panel flush>
      <div className="proc-tools">
        <label className="sr-only" htmlFor="proc-filter">
          Filter applications
        </label>
        <input
          id="proc-filter"
          className="input"
          type="search"
          placeholder="Filter applications"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <span className="proc-count">
          {shown.length === rows.length ? `${rows.length} applications` : `${shown.length} of ${rows.length} applications`}
          {" \u00B7 top by CPU and memory"}
        </span>
      </div>

      {!snap.cpuAvailable && (
        <p className="state state-warning" style={{ margin: "var(--space-3) var(--space-4)" }}>
          Per-application CPU needs two samples to compare. It appears after the next refresh and is never shown as 0%.
        </p>
      )}

      <DataTable
        key={String(snap.cpuAvailable)}
        caption="Applications by CPU and memory use"
        columns={columns}
        rows={shown}
        getRowKey={(g) => g.name}
        defaultSort={{ key: snap.cpuAvailable ? "cpu" : "mem", dir: "desc" }}
        emptyMessage={needle ? "No application matches the filter." : "No application is using measurable resources right now."}
      />
    </Panel>
  );
}

export default function Processes() {
  const state = usePolling(getProcesses, PROCESS_POLL_MS);
  const captured = state.data?.capturedAt;

  return (
    <div className="page">
      <PageHeader
        title="Processes"
        subtitle="What is using this machine right now, grouped by application."
        meta={captured ? <span>Captured {formatTime(captured)} &middot; every 30 s</span> : null}
      />

      <DataBoundary state={state} what="process snapshot">
        {(snap) =>
          !snap.available ? (
            <Panel title="Process insight">
              <EmptyState>Process data is unavailable. {snap.note}</EmptyState>
            </Panel>
          ) : (
            <>
              <section className="panel" aria-label="Snapshot">
                <StatStrip
                  flat
                  label="Snapshot"
                  items={[
                    { label: "Processes", value: snap.processCount.toLocaleString() },
                    { label: "Threads", value: snap.threadCount.toLocaleString() },
                    { label: "System uptime", value: formatUptime(snap.uptimeSeconds) },
                    {
                      label: "CPU in use (listed apps)",
                      value: snap.cpuAvailable ? pct(snap.totalCpuPercent) : "Measuring\u2026",
                      sub: snap.cpuAvailable ? undefined : "Needs a second sample",
                    },
                  ]}
                />
              </section>
              <ProcessTable snap={snap} />
            </>
          )
        }
      </DataBoundary>

      <p className="footnote">
        Program names and resource use only: command lines, user names and file paths are never collected, and nothing
        here is stored.
        {state.data?.note ? ` ${state.data.note}` : ""}
      </p>
    </div>
  );
}
