import UtilizationBar from "./UtilizationBar";

/**
 * One headline reading. A missing value is shown as an em dash with the
 * backend's own reason, never as 0 and never as a bar.
 * tile: { key, label, number, unit, percent, level, sub, note }
 */
export default function MetricTile({ tile }) {
  const missing = tile.number === null || tile.number === undefined;
  return (
    <div className="metric" role="group" aria-label={tile.label}>
      <div className="metric-label">{tile.label}</div>
      <div className={`metric-value${missing ? " metric-value--na" : ""}`}>
        {missing ? (
          <span>&mdash;</span>
        ) : (
          <>
            <span>{tile.number}</span>
            {tile.unit && <span className="metric-unit">{tile.unit}</span>}
          </>
        )}
      </div>
      {!missing && tile.percent !== null && tile.percent !== undefined && (
        <UtilizationBar value={tile.percent} level={tile.level ?? "normal"} label={`${tile.label} utilization`} />
      )}
      {!missing && tile.sub && <div className="metric-sub">{tile.sub}</div>}
      {missing && tile.note && (
        <div className="metric-note" title={tile.note}>
          {tile.note}
        </div>
      )}
    </div>
  );
}
