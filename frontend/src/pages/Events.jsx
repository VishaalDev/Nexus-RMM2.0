import DataBoundary from "../components/DataBoundary";
import DataTable from "../components/DataTable";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import StatusBadge from "../components/StatusBadge";
import { useEvents } from "../hooks/useApiData";
import { formatDateTime } from "../utils/format";

const LEVEL_RANK = { CRITICAL: 0, ERROR: 1, WARNING: 2 };

const COLUMNS = [
  {
    key: "time",
    header: "Time",
    nowrap: true,
    sortValue: (e) => Date.parse(e.occurredAt) || null,
    render: (e) => <span className="num">{formatDateTime(e.occurredAt)}</span>,
  },
  {
    key: "level",
    header: "Level",
    nowrap: true,
    sortValue: (e) => LEVEL_RANK[e.level?.toUpperCase()] ?? 9,
    sortFirst: "asc",
    render: (e) => <StatusBadge status={e.level?.toUpperCase()} label={e.level} />,
  },
  { key: "category", header: "Category", sortValue: (e) => e.category, render: (e) => e.category },
  { key: "provider", header: "Provider", mono: true, wrap: "anywhere", sortValue: (e) => e.providerName, render: (e) => e.providerName },
  { key: "message", header: "Message", wrap: "words", minWidth: "20rem", render: (e) => e.message },
];

export default function Events() {
  const events = useEvents(100);

  return (
    <div className="page">
      <PageHeader
        title="Events"
        subtitle="Critical and error entries from the Windows System log, limited to hardware-relevant providers (WHEA, disk, power, PnP)."
        meta={events.data ? <span>{events.data.length} shown &middot; every 30 s</span> : null}
      />

      <Panel title="Hardware and system events" flush>
        <DataBoundary state={events} what="events">
          {(data) => (
            <DataTable
              caption="Hardware and system events"
              columns={COLUMNS}
              rows={data}
              getRowKey={(e) => `${e.providerName}|${e.recordId}`}
              defaultSort={{ key: "time", dir: "desc" }}
              emptyMessage="No matching hardware/system events detected."
            />
          )}
        </DataBoundary>
      </Panel>
    </div>
  );
}
