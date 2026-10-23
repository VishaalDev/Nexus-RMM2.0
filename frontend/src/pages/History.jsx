import { useMemo, useState } from "react";
import MetricHistoryChart from "../components/MetricHistoryChart";
import Panel from "../components/Panel";
import SelectField from "../components/SelectField";
import { LoadingState } from "../components/States";
import { useCurrentMetrics } from "../hooks/useApiData";
import { LIVE_TABLE_METRICS, METRIC_LABELS, readingsOfType } from "../utils/metrics";

const WINDOW_OPTIONS = [
  { value: "1h", label: "Last 1 hour" },
  { value: "6h", label: "Last 6 hours" },
  { value: "24h", label: "Last 24 hours" },
  { value: "7d", label: "Last 7 days" },
  { value: "30d", label: "Last 30 days" },
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
      <header className="page-header">
        <h2>History</h2>
        <p>Stored telemetry for any metric this backend collects.</p>
      </header>

      <Panel title="Chart">
        <div className="controls" style={{ marginBottom: "var(--space-4)" }}>
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
              <SelectField
                label="Instance"
                value={effectiveInstance ?? ""}
                options={instanceOptions}
                onChange={setInstance}
              />
            )
          )}
          <SelectField label="Range" value={range} options={WINDOW_OPTIONS} onChange={setRange} />
        </div>

        <MetricHistoryChart
          key={`${metric}|${effectiveInstance ?? ""}|${range}`}
          metric={metric}
          instance={effectiveInstance}
          range={range}
        />
      </Panel>
    </div>
  );
}
