import { useMemo, useState } from "react";
import MetricHistoryChart from "../components/MetricHistoryChart";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import Segmented from "../components/Segmented";
import SelectField from "../components/SelectField";
import { LoadingState } from "../components/States";
import { useCurrentMetrics } from "../hooks/useApiData";
import { LIVE_TABLE_METRICS, METRIC_LABELS, readingsOfType } from "../utils/metrics";

const WINDOW_OPTIONS = [
  { value: "1h", label: "1 h" },
  { value: "6h", label: "6 h" },
  { value: "24h", label: "24 h" },
  { value: "7d", label: "7 d" },
  { value: "30d", label: "30 d" },
];

const METRIC_OPTIONS = LIVE_TABLE_METRICS.map((type) => ({ value: type, label: METRIC_LABELS[type] }));

export default function History() {
  const current = useCurrentMetrics();
  const [metric, setMetric] = useState("CPU_UTILIZATION");
  const [instance, setInstance] = useState("");
  const [range, setRange] = useState("1h");

  const instanceOptions = useMemo(() => {
    const labels = [
      ...new Set(readingsOfType(current.data, metric).map((r) => r.instanceLabel).filter(Boolean)),
    ].sort();
    return labels.map((label) => ({ value: label, label }));
  }, [current.data, metric]);

  const isMultiInstance = instanceOptions.length > 0;
  const effectiveInstance = isMultiInstance
    ? instanceOptions.some((o) => o.value === instance)
      ? instance
      : instanceOptions[0].value
    : null;

  return (
    <div className="page">
      <PageHeader title="History" subtitle="Stored telemetry for any metric this backend collects." />

      <Panel
        title={METRIC_LABELS[metric]}
        actions={<Segmented label="Time range" value={range} options={WINDOW_OPTIONS} onChange={setRange} />}
      >
        <div className="toolbar history-toolbar">
          <SelectField
            label="Metric"
            value={metric}
            options={METRIC_OPTIONS}
            onChange={(value) => {
              setMetric(value);
              setInstance("");
            }}
          />
          {current.loading && !current.data ? (
            <LoadingState>Loading instances&hellip;</LoadingState>
          ) : (
            isMultiInstance && (
              <SelectField label="Instance" value={effectiveInstance ?? ""} options={instanceOptions} onChange={setInstance} />
            )
          )}
        </div>

        <MetricHistoryChart
          key={`${metric}|${effectiveInstance ?? ""}|${range}`}
          metric={metric}
          instance={effectiveInstance}
          range={range}
          tall
        />
      </Panel>
    </div>
  );
}
