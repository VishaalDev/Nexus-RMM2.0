import { useMemo } from "react";
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { EmptyState } from "./States";
import { formatRate } from "../utils/format";

/** A trend needs at least two real readings; fewer is "insufficient data", not a flat line. */
const MIN_REAL_POINTS = 2;
const DAY_MS = 86_400_000;
/**
 * Room for the last x-axis label, which is centred on the final point and
 * would otherwise be cut off at the container's right edge.
 */
const CHART_MARGIN = { top: 8, right: 28, bottom: 0, left: 0 };

/** Axis ticks are whole numbers so "100%" fits; tooltips keep one decimal. */
function formatTick(value, unit) {
  if (unit === "%" || unit === "\u00B0C") return `${Math.round(value)}${unit}`;
  return formatValue(value, unit);
}

function formatValue(value, unit) {
  if (value === null || value === undefined) return "N/A";
  if (unit === "B/s") return formatRate(value);
  if (unit === "%") return `${Number(value).toFixed(1)}%`;
  return `${Number(value).toFixed(1)} ${unit}`;
}

/**
 * Line chart over real stored samples. Samples with no value (sensor
 * unavailable that cycle) stay in the series as null so the line shows a
 * genuine gap rather than being silently bridged.
 */
export default function HistoryChart({
  points,
  unit,
  domain,
  yAxisWidth,
  seriesName,
  height = 230,
  emptyMessage = "Insufficient historical data. Continue monitoring to build history.",
}) {
  const { data, realCount, gapCount, spansDays } = useMemo(() => {
    const mapped = points.map((p) => ({ t: Date.parse(p.timestamp), value: p.value }));
    const real = mapped.filter((p) => p.value !== null && p.value !== undefined).length;
    const span = mapped.length > 1 ? mapped[mapped.length - 1].t - mapped[0].t : 0;
    return {
      data: mapped,
      realCount: real,
      gapCount: mapped.length - real,
      spansDays: span > DAY_MS,
    };
  }, [points]);

  if (realCount < MIN_REAL_POINTS) {
    return <EmptyState>{emptyMessage}</EmptyState>;
  }

  const tickFormat = (ms) =>
    spansDays
      ? new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" })
      : new Date(ms).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

  return (
    <>
      <div style={{ width: "100%", height, minWidth: 0 }}>
        <ResponsiveContainer>
          <LineChart data={data} margin={CHART_MARGIN}>
            <CartesianGrid stroke="var(--color-border)" vertical={false} />
            <XAxis
              dataKey="t"
              type="number"
              scale="time"
              domain={["dataMin", "dataMax"]}
              tickFormatter={tickFormat}
              stroke="var(--color-text-muted)"
              fontSize={11}
              tickLine={false}
              axisLine={{ stroke: "var(--color-border-strong)" }}
              minTickGap={56}
              interval="preserveStartEnd"
            />
            <YAxis
              domain={domain}
              tickFormatter={(v) => formatTick(v, unit)}
              stroke="var(--color-text-muted)"
              fontSize={11}
              tickLine={false}
              axisLine={false}
              width={yAxisWidth}
            />
            <Tooltip
              labelFormatter={(ms) => new Date(ms).toLocaleString()}
              formatter={(v) => [formatValue(v, unit), seriesName]}
              cursor={{ stroke: "var(--color-border-strong)" }}
              contentStyle={{
                background: "var(--color-raised)",
                border: "1px solid var(--color-border-strong)",
                borderRadius: 4,
                color: "var(--color-text)",
                fontSize: "12px",
                boxShadow: "var(--shadow-pop)",
              }}
              labelStyle={{ color: "var(--color-text-muted)" }}
            />
            <Line
              type="monotone"
              dataKey="value"
              stroke="var(--color-accent)"
              dot={false}
              activeDot={{ r: 3 }}
              strokeWidth={1.75}
              connectNulls={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
      {gapCount > 0 && (
        <p className="note">
          {gapCount} sample{gapCount === 1 ? "" : "s"} in this window had no reading (sensor
          unavailable) and appear as gaps.
        </p>
      )}
    </>
  );
}
