import DataBoundary from "../components/DataBoundary";
import NetworkAdaptersTable from "../components/NetworkAdaptersTable";
import Panel from "../components/Panel";
import { useCurrentMetrics, useHardwareSnapshot } from "../hooks/useApiData";

export default function Network() {
  const hardware = useHardwareSnapshot();
  const current = useCurrentMetrics();

  return (
    <div className="page">
      <header className="page-header">
        <h2>Network</h2>
        <p>
          Adapters, link speed and live receive/transmit activity. Adapters that are down report
          no throughput rather than zero.
        </p>
      </header>

      {current.error && current.data && (
        <p className="state state-warning" role="alert">
          Live throughput may be out of date: {current.error.message}
        </p>
      )}

      <Panel title="Adapters" actions="Throughput refreshes every 10 seconds">
        <DataBoundary state={hardware} what="network adapters">
          {(data) => <NetworkAdaptersTable adapters={data.networkAdapters} current={current.data ?? []} />}
        </DataBoundary>
      </Panel>
    </div>
  );
}
