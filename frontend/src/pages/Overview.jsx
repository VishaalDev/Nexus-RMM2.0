import { useState } from "react";
import AttentionPanel from "../components/AttentionPanel";
import DataBoundary from "../components/DataBoundary";
import LiveMetricsTable from "../components/LiveMetricsTable";
import MetricHistoryChart from "../components/MetricHistoryChart";
import MetricTile from "../components/MetricTile";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import Segmented from "../components/Segmented";
import SystemIdentity from "../components/SystemIdentity";
import { useCurrentMetrics, useDevice, useHardwareSnapshot } from "../hooks/useApiData";
import { buildLiveRows } from "../utils/metrics";
import { buildOverviewTiles } from "../utils/overview";
import { describeSystem } from "../utils/system";

const CHART_METRICS = [
  { value: "CPU_UTILIZATION", label: "CPU" },
  { value: "RAM_UTILIZATION", label: "Memory" },
];

export default function Overview() {
  const device = useDevice();
  const hardware = useHardwareSnapshot();
  const current = useCurrentMetrics();
  const [chartMetric, setChartMetric] = useState("CPU_UTILIZATION");
  const { sys, dev, uptime, os } = describeSystem(device, hardware);
  const host = sys?.hostname ?? dev?.hostname;

  const meta = host ? (
    <>
      <span>{host}</span>
      {os && <span>{os}</span>}
      {uptime && <span>Up {uptime}</span>}
    </>
  ) : null;

  return (
    <div className="page">
      <PageHeader title="Overview" meta={meta} />

      <AttentionPanel />

      {current.data && (
        <section className="metric-strip" aria-label="Current readings">
          {buildOverviewTiles(current.data, hardware.data).map((tile) => (
            <MetricTile key={tile.key} tile={tile} />
          ))}
        </section>
      )}

      <div className="grid-main-side">
        <Panel
          title="Last hour"
          actions={<Segmented label="Chart metric" value={chartMetric} options={CHART_METRICS} onChange={setChartMetric} />}
        >
          <MetricHistoryChart key={`${chartMetric}|1h`} metric={chartMetric} range="1h" height={260} />
        </Panel>
        <SystemIdentity device={device} hardware={hardware} />
      </div>

      <Panel title="Live sensors" actions="Every 10 s" flush>
        <DataBoundary state={current} what="live metrics">
          {(data) => <LiveMetricsTable rows={buildLiveRows(data)} />}
        </DataBoundary>
      </Panel>
    </div>
  );
}
