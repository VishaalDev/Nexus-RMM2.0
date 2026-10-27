import DataBoundary from "../components/DataBoundary";
import NetworkAdaptersTable from "../components/NetworkAdaptersTable";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import StatStrip from "../components/StatStrip";
import { useCurrentMetrics, useHardwareSnapshot } from "../hooks/useApiData";
import { formatRate } from "../utils/format";
import { readingsOfType } from "../utils/metrics";

const available = (r) => r.availabilityStatus === "AVAILABLE" && r.value !== null && r.value !== undefined;
const total = (readings) => {
  const live = readings.filter(available);
  return live.length === 0 ? null : live.reduce((sum, r) => sum + r.value, 0);
};

function Summary({ adapters, current }) {
  const up = adapters.filter((a) => a.operStatus === "UP").length;
  const rx = total(readingsOfType(current, "NETWORK_RX_BYTES_PER_SEC"));
  const tx = total(readingsOfType(current, "NETWORK_TX_BYTES_PER_SEC"));
  return (
    <section className="panel" aria-label="Network summary">
      <StatStrip
        flat
        label="Network summary"
        items={[
          { label: "Adapters up", value: `${up} of ${adapters.length}` },
          { label: "Receive, all adapters", value: rx === null ? "\u2014" : formatRate(rx) },
          { label: "Transmit, all adapters", value: tx === null ? "\u2014" : formatRate(tx) },
        ]}
      />
    </section>
  );
}

export default function Network() {
  const hardware = useHardwareSnapshot();
  const current = useCurrentMetrics();

  return (
    <div className="page">
      <PageHeader
        title="Network"
        subtitle="Adapters that are down report no throughput rather than zero."
        meta={<span>Throughput every 10 s</span>}
      />

      {current.error && current.data && (
        <p className="state state-warning" role="alert">
          Live throughput may be out of date: {current.error.message}
        </p>
      )}

      <DataBoundary state={hardware} what="network adapters">
        {(data) => (
          <>
            <Summary adapters={data.networkAdapters} current={current.data ?? []} />
            <Panel title="Adapters" flush>
              <NetworkAdaptersTable adapters={data.networkAdapters} current={current.data ?? []} />
            </Panel>
          </>
        )}
      </DataBoundary>
    </div>
  );
}
