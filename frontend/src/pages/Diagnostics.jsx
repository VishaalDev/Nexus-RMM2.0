import DataBoundary from "../components/DataBoundary";
import DataTable from "../components/DataTable";
import Panel from "../components/Panel";
import SpecTable from "../components/SpecTable";
import StatusBadge from "../components/StatusBadge";
import { useDiagnostics } from "../hooks/useApiData";
import { formatDateTime } from "../utils/format";

const PROVIDER_COLUMNS = [
  {
    key: "types",
    header: "Metric type(s)",
    mono: true,
    wrap: "anywhere",
    minWidth: "13rem",
    // One metric type per line: each is a long identifier with no natural break points.
    render: (p) => (
      <div className="cell-lines">
        {p.metricTypes.map((t) => (
          <div key={t}>{t}</div>
        ))}
      </div>
    ),
  },
  {
    key: "supported",
    header: "Supported",
    nowrap: true,
    render: (p) => <StatusBadge status={p.supported ? "AVAILABLE" : "UNSUPPORTED"} label={p.supported ? "Yes" : "No"} />,
  },
  {
    key: "available",
    header: "Available now",
    nowrap: true,
    render: (p) => <StatusBadge status={p.available ? "AVAILABLE" : "UNAVAILABLE"} label={p.available ? "Yes" : "No"} />,
  },
  { key: "source", header: "Source", mono: true, wrap: "anywhere", minWidth: "10rem", render: (p) => p.source },
  { key: "diagnostic", header: "Diagnostic", wrap: "words", minWidth: "14rem", render: (p) => p.diagnostic },
];

function EventMonitoringPanel({ status }) {
  const rows = [
    ["Applicable on this platform", status.applicable ? "Yes" : "No"],
    ["Last poll succeeded", status.applicable ? (status.lastPollSuccess ? "Yes" : "No") : null],
    ["Last poll at", status.lastPollAt ? formatDateTime(status.lastPollAt) : null],
    ["New events on last poll", status.applicable ? status.lastNewEventCount : null],
    ["Last error", status.lastError],
  ];
  return (
    <Panel title="Event monitoring">
      <SpecTable rows={rows} caption="Windows event log monitoring status" />
    </Panel>
  );
}

export default function Diagnostics() {
  const diagnostics = useDiagnostics();

  return (
    <div className="page">
      <header className="page-header">
        <h2>Diagnostics</h2>
        <p>Live application, storage and provider capability status.</p>
      </header>

      <DataBoundary state={diagnostics} what="diagnostics">
        {(data) => (
          <>
            <Panel title="Application">
              <SpecTable
                caption="Application status"
                rows={[
                  ["Status", <StatusBadge key="s" status={data.applicationStatus === "UP" ? "AVAILABLE" : "ERROR"} label={data.applicationStatus} />],
                  ["Computer ID", data.computerId, true],
                  ["Hostname", data.hostname],
                  ["Collection interval", `${data.collectionIntervalSeconds}s`],
                  ["Database path", data.databasePath, true],
                  ["Stored samples", data.totalStoredSamples.toLocaleString()],
                  ["Stored events", data.totalStoredEvents.toLocaleString()],
                ]}
              />
            </Panel>

            <Panel title="Provider capability matrix">
              <DataTable
                caption="Hardware provider status"
                stack
                columns={PROVIDER_COLUMNS}
                rows={data.providers}
                getRowKey={(p) => p.metricTypes.join(",")}
                emptyMessage="No providers are registered."
              />
            </Panel>

            <EventMonitoringPanel status={data.eventMonitoring} />
          </>
        )}
      </DataBoundary>
    </div>
  );
}
