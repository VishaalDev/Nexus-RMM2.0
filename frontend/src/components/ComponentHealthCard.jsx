import FindingsList from "./FindingsList";
import HealthBadge from "./HealthBadge";
import ScoreMeter from "./ScoreMeter";
import { STATUS_META, formatPercent } from "../utils/health";

/**
 * One component's health. A NO_DATA component shows WHY it could not be
 * assessed (the backend's own reason) instead of any score; its `basis`
 * (what the number is and is not based on) is one click away.
 */
export default function ComponentHealthCard({ component }) {
  const { label, score, status, basis, dataCoverage, findings, instances, noDataReason, weight } = component;
  const noData = status === "NO_DATA";
  const color = STATUS_META[status]?.color;
  const unscoredInstances = (instances ?? []).filter((i) => i.status === "NO_DATA" && i.instance);
  const scoredInstances = (instances ?? []).filter((i) => i.status !== "NO_DATA" && i.instance);

  return (
    <article className="hl-card" aria-label={label}>
      <header className="hl-card-header">
        <h4>{label}</h4>
        <HealthBadge value={status} />
      </header>

      <ScoreMeter score={score} label={`${label} health score`} color={color} />

      {noData ? (
        <>
          <p className="hl-insufficient">Insufficient data &mdash; not scored</p>
          <p className="hl-no-data">{noDataReason}</p>
        </>
      ) : (
        <p className="muted hl-coverage">
          Telemetry coverage {formatPercent(dataCoverage)} &middot; weight in overall score {weight}
        </p>
      )}

      <FindingsList findings={findings} />

      {scoredInstances.length > 0 && (
        <ul className="hl-instances" aria-label={`${label} instances`}>
          {scoredInstances.map((i) => (
            <li key={i.instance}>
              <span className="mono">{i.instance}</span>
              <span className="num">{i.score}</span>
            </li>
          ))}
        </ul>
      )}
      {!noData && unscoredInstances.length > 0 && (
        <p className="muted hl-coverage">Not assessed: {unscoredInstances.map((i) => i.instance).join(", ")}</p>
      )}

      {basis && (
        <details className="disclosure hl-basis">
          <summary>Basis</summary>
          <p className="muted">{basis}</p>
        </details>
      )}
    </article>
  );
}
