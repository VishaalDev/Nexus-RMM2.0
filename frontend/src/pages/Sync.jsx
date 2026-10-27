import { useState } from "react";
import DataBoundary from "../components/DataBoundary";
import DataTable from "../components/DataTable";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import SpecTable from "../components/SpecTable";
import StatusBadge from "../components/StatusBadge";
import { triggerSync } from "../api/services/syncService";
import { useSyncStatus } from "../hooks/useSyncData";
import { formatDateTime } from "../utils/format";
import "../styles/sync.css";

const TYPE_LABELS = {
  METRIC_SAMPLE: "Metric samples",
  HARDWARE_EVENT: "Hardware events",
};

const TYPE_COLUMNS = [
  { key: "type", header: "Data", render: (r) => r.label },
  { key: "pending", header: "Pending", nowrap: true, render: (r) => r.pending.toLocaleString() },
  { key: "synced", header: "Synchronized", nowrap: true, render: (r) => r.synced.toLocaleString() },
  { key: "failed", header: "Failed", nowrap: true, render: (r) => r.failed.toLocaleString() },
];

function Callout({ title, tone, children }) {
  const cls = `sync-callout${tone ? ` sync-callout--${tone}` : ""}`;
  return (
    <div className={cls} role={tone === "error" ? "alert" : undefined}>
      <h4>{title}</h4>
      {children}
    </div>
  );
}

function CountTile({ label, hint, value, tone }) {
  return (
    <div className={`sync-count${tone ? ` sync-count--${tone}` : ""}`}>
      <div className="sync-count-value">{value.toLocaleString()}</div>
      <div className="sync-count-label">{label}</div>
      <div className="sync-count-hint">{hint}</div>
    </div>
  );
}

function SyncNowPanel({ status, refetch }) {
  const [triggering, setTriggering] = useState(false);
  const [result, setResult] = useState(null);
  const [actionError, setActionError] = useState(null);

  const configured = status.configuration.configured;
  const busy = status.running || triggering;
  const disabled = busy || !configured;

  async function handleSyncNow() {
    setTriggering(true);
    setResult(null);
    setActionError(null);
    try {
      const response = await triggerSync();
      setResult(response.message);
    } catch (err) {
      // 409 answers (already running, not configured) carry a real message from the backend.
      setActionError(err?.message ?? "The synchronization request failed.");
    } finally {
      setTriggering(false);
      refetch();
    }
  }

  return (
    <Panel title="Synchronization" actions={<StatusBadge status={status.state} />}>
      <div className="sync-actions">
        <button type="button" className="btn btn--primary" disabled={disabled} aria-busy={busy} onClick={handleSyncNow}>
          {busy ? "Syncing\u2026" : "Sync Now"}
        </button>
        {!configured && (
          <span className="sync-note">Sync Now is available once a valid destination is configured.</span>
        )}
        {configured && status.running && <span className="sync-note">A synchronization is in progress.</span>}
      </div>
      {result && (
        <p className="sync-note" role="status">
          {result}
        </p>
      )}
      {actionError && (
        <p className="state state-error" role="alert">
          Could not start synchronization. {actionError}
        </p>
      )}
    </Panel>
  );
}

export default function Sync() {
  const syncStatus = useSyncStatus();

  return (
    <div className="page">
      <PageHeader
        title="Sync"
        subtitle="Data is stored locally first and counts as synchronized only once the destination has acknowledged it."
      />

      <DataBoundary state={syncStatus} what="synchronization status">
        {(status) => {
          const cfg = status.configuration;
          const typeRows = Object.entries(status.queue.byType ?? {}).map(([type, c]) => ({
            type,
            label: TYPE_LABELS[type] ?? type,
            ...c,
          }));

          return (
            <>
              <SyncNowPanel status={status} refetch={syncStatus.refetch} />

              {status.nextStep && (
                <Callout title="Next step" tone={status.state === "CONFIG_INVALID" ? "error" : "warning"}>
                  <p>{status.nextStep}</p>
                </Callout>
              )}

              {status.lastError && (
                <Callout title={`Last error: ${status.lastError.category.replace(/_/g, " ").toLowerCase()}`} tone="error">
                  <p>{status.lastError.message}</p>
                  {status.lastError.at && <p className="sync-note">{formatDateTime(status.lastError.at)}</p>}
                </Callout>
              )}

              <Panel title="Queue" flush>
                <div className="sync-counts">
                  <CountTile label="Pending" hint="Stored locally, not yet acknowledged" value={status.queue.pending} />
                  <CountTile label="Synchronized" hint="Acknowledged by destination" value={status.queue.synced} />
                  <CountTile
                    label="Failed"
                    hint="Permanently rejected by destination"
                    value={status.queue.failed}
                    tone={status.queue.failed > 0 ? "critical" : undefined}
                  />
                </div>
                <DataTable
                  caption="Queue by data type"
                  stack
                  columns={TYPE_COLUMNS}
                  rows={typeRows}
                  getRowKey={(r) => r.type}
                  emptyMessage="No queue data."
                />
              </Panel>

              <div className="grid-2">
              <Panel title="Destination connectivity" actions={<StatusBadge status={status.connectivity} />}>
                <p className="sync-note sync-note--block">
                  Based on the last real delivery attempt, not a live probe. Unreachable means no HTTP answer was
                  received; a destination that answers with an error is reachable but failing.
                </p>
                <SpecTable
                  caption="Connectivity details"
                  rows={[
                    ["Last attempt", status.lastAttemptAt ? formatDateTime(status.lastAttemptAt) : "No attempt yet"],
                    [
                      "Last attempt result",
                      status.lastAttemptResult ? <StatusBadge key="r" status={status.lastAttemptResult} /> : "None yet",
                    ],
                    ["Last successful synchronization", status.lastSuccessfulSyncAt ? formatDateTime(status.lastSuccessfulSyncAt) : "Never"],
                    ["Consecutive failures", status.consecutiveFailures],
                    ["Next automatic retry", status.nextAttemptAt ? formatDateTime(status.nextAttemptAt) : "None scheduled"],
                  ]}
                />
              </Panel>

              <Panel title="Configuration" actions={<StatusBadge status={cfg.configured ? "AVAILABLE" : cfg.destinationSet ? "ERROR" : "NOT_CONFIGURED"} label={cfg.configured ? "Configured" : cfg.destinationSet ? "Invalid" : "Not configured"} />}>
                <SpecTable
                  caption="Synchronization configuration"
                  rows={[
                    ["Destination", cfg.destination ?? "Not set", Boolean(cfg.destination)],
                    ["Credentials", cfg.authConfigured ? "Auth token configured (value hidden)" : "No auth token"],
                    ["Batch size", cfg.batchSize],
                    ["Automatic attempt interval", `${cfg.pollIntervalSeconds}s`],
                  ]}
                />
                {cfg.issue && (
                  <Callout title="Configuration problem" tone="error">
                    <p>{cfg.issue}</p>
                  </Callout>
                )}
                {cfg.warnings?.map((w) => (
                  <Callout key={w} title="Warning" tone="warning">
                    <p>{w}</p>
                  </Callout>
                ))}
                {!cfg.destinationSet && (
                  <p className="sync-note">
                    To enable synchronization set <code>nexus.sync.destination-url</code> (or the environment variable{" "}
                    <code>NEXUS_SYNC_DESTINATION_URL</code>) and restart the backend. See <code>docs/sync.md</code>.
                  </p>
                )}
              </Panel>
              </div>
            </>
          );
        }}
      </DataBoundary>
    </div>
  );
}
