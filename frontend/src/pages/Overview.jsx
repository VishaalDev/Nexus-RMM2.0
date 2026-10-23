import DataBoundary from "../components/DataBoundary";
import LiveMetricsTable from "../components/LiveMetricsTable";
import MetricHistoryChart from "../components/MetricHistoryChart";
import Panel from "../components/Panel";
import SystemIdentity from "../components/SystemIdentity";
import { useCurrentMetrics, useDevice, useHardwareSnapshot } from "../hooks/useApiData";
import { buildLiveRows } from "../utils/metrics";

export default function Overview() {
  const device = useDevice();
  const hardware = useHardwareSnapshot();
  const current = useCurrentMetrics();

  return (
    <div className="page">
      <header className="page-header">
        <h2>Overview</h2>
        <p>Real readings from this machine&rsquo;s hardware. Nothing on this page is simulated.</p>
      </header>

      <SystemIdentity device={device} hardware={hardware} />

      <Panel title="Live monitoring" actions="Refreshes every 10 seconds">
        <DataBoundary state={current} what="live metrics">
          {(data) => <LiveMetricsTable rows={buildLiveRows(data)} />}
        </DataBoundary>
      </Panel>

      <Panel title="CPU utilization, last hour">
        <MetricHistoryChart key="CPU_UTILIZATION|1h" metric="CPU_UTILIZATION" range="1h" />
      </Panel>
    </div>
  );
}
