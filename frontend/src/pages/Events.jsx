import DataBoundary from "../components/DataBoundary";
import DataTable from "../components/DataTable";
import Panel from "../components/Panel";
import StatusBadge from "../components/StatusBadge";
import { useEvents } from "../hooks/useApiData";
import { formatDateTime } from "../utils/format";

const COLUMNS = [
  { key: "time", header: "Time", mono: true, render: (e) => formatDateTime(e.occurredAt) },
  { key: "level", header: "Level", render: (e) => <StatusBadge status={e.level?.toUpperCase()} label={e.level} /> },
  { key: "category", header: "Category", render: (e) => e.category },
  { key: "provider", header: "Provider", mono: true, render: (e) => e.providerName },
  { key: "message", header: "Message", render: (e) => e.message },
];

export default function Events() {
  const events = useEvents(100);

  return (
    <div className="page">
      <header className="page-header">
        <h2>Events</h2>
        <p>
          Critical and error entries from the Windows System log, limited to hardware-relevant
          providers (WHEA, disk, power, PnP). Routine activity is not shown here.
        </p>
      </header>

      <Panel title="Recent hardware/system events" actions="Refreshes every 30 seconds">
        <DataBoundary state={events} what="events">
          {(data) =>
            data.length === 0 ? (
              <p className="state">No matching hardware/system events detected.</p>
            ) : (
              <DataTable
                caption="Hardware and system events"
                columns={COLUMNS}
                rows={data}
                getRowKey={(e) => `${e.providerName}|${e.recordId}`}
                emptyMessage="No matching hardware/system events detected."
              />
            )
          }
        </DataBoundary>
      </Panel>
    </div>
  );
}
