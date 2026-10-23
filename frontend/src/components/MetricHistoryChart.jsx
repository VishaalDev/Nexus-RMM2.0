import DataBoundary from "./DataBoundary";
import HistoryChart from "./HistoryChart";
import { useMetricHistory } from "../hooks/useApiData";
import { METRIC_LABELS, chartConfigFor } from "../utils/metrics";

/**
 * Self-contained history view for one (metric, instance, range). Used by
 * the Overview (CPU, last hour) and the History page (any metric), so the
 * fetch/format wiring exists once. Callers should give it a `key` built
 * from its props so switching selection remounts it with fresh state.
 */
export default function MetricHistoryChart({ metric, instance = null, range }) {
  const state = useMetricHistory(metric, instance, range);
  const config = chartConfigFor(metric);

  return (
    <DataBoundary state={state} what="history">
      {(points) => (
        <HistoryChart
          points={points}
          unit={config.unit}
          domain={config.domain}
          yAxisWidth={config.yAxisWidth}
          seriesName={METRIC_LABELS[metric] ?? metric}
        />
      )}
    </DataBoundary>
  );
}
