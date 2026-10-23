import ComponentHealthCard from "../components/ComponentHealthCard";
import DataBoundary from "../components/DataBoundary";
import HealthBadge from "../components/HealthBadge";
import Panel from "../components/Panel";
import ScoreMeter from "../components/ScoreMeter";
import { useHealthReport } from "../hooks/useHealthData";
import { formatDateTime } from "../utils/format";
import { STATUS_META, averageTelemetryCoverage, countScored, formatPercent } from "../utils/health";
import "../styles/health.css";

/**
 * Four different answers to "how much of this score is backed by data?",
 * kept apart on purpose:
 *  - assessed components:  how many components produced a score at all
 *  - component-weight coverage: the backend's report-level dataCoverage - the
 *    share of the overall score's total weight that had data
 *  - telemetry coverage:   per component, valid samples in the last hour vs.
 *    the number expected at the collection interval (averaged here over the
 *    assessed components; each card shows its own)
 *  - insufficient data:    components with no score. They are excluded from the
 *    overall score and are never counted as healthy.
 */
function CoverageSummary({ data }) {
  const { scored, total } = countScored(data.components);
  const insufficient = data.components.filter((c) => c.score === null || c.score === undefined);
  const telemetry = averageTelemetryCoverage(data.components);

  return (
    <>
      <dl className="hl-coverage-grid" aria-label="Coverage of this score">
        <div>
          <dt>Assessed components</dt>
          <dd>
            {scored} of {total}
          </dd>
          <p className="muted">Have a score and feed the overall score.</p>
        </div>
        <div>
          <dt>Component-weight coverage</dt>
          <dd>{formatPercent(data.dataCoverage)}</dd>
          <p className="muted">Share of the overall score&rsquo;s total weight that had data.</p>
        </div>
        <div>
          <dt>Telemetry coverage</dt>
          <dd>{telemetry === null ? "\u2014" : formatPercent(telemetry)}</dd>
          <p className="muted">
            Average over assessed components: valid samples in the last hour versus the number expected.
            Each card shows its own.
          </p>
        </div>
        <div>
          <dt>Insufficient data</dt>
          <dd>
            {insufficient.length} component{insufficient.length === 1 ? "" : "s"}
          </dd>
          <p className="muted">
            {insufficient.length > 0
              ? `${insufficient.map((c) => c.label).join(", ")}: not scored, excluded from the overall score, never counted as healthy.`
              : "Every component produced a score."}
          </p>
        </div>
      </dl>
      {scored < total && (
        <p className="hl-caution" role="note">
          The overall score reflects only the {scored} assessed component{scored === 1 ? "" : "s"}. Components
          without data could raise or lower it once they can be assessed.
        </p>
      )}
    </>
  );
}

export default function Health() {
  const report = useHealthReport();
  return (
    <div className="page">
      <header className="page-header">
        <h2>Health</h2>
        <p>
          Component health scores computed from the telemetry Module 1 has collected. They are
          heuristics, not a diagnosis. &ldquo;No data&rdquo; means a component could not be
          assessed &mdash; it is never counted as healthy.
        </p>
      </header>

      <DataBoundary state={report} what="health analysis">
        {(data) => {
          return (
            <>
              <Panel title="Overall health" actions="Refreshes every 30 seconds">
                <div className="hl-overall">
                  <div className="hl-overall-score">
                    <ScoreMeter
                      score={data.overallScore}
                      label="Overall health score"
                      color={STATUS_META[data.overallStatus]?.color}
                    />
                    <HealthBadge value={data.overallStatus} />
                  </div>
                  <div>
                    <p className="muted">{data.overallBasis}</p>
                    <p className="muted">Generated {formatDateTime(data.generatedAt)}</p>
                  </div>
                </div>
                <CoverageSummary data={data} />
              </Panel>

              <div className="hl-grid">
                {data.components.map((c) => (
                  <ComponentHealthCard key={c.component} component={c} />
                ))}
              </div>

              <Panel title="What this report cannot see">
                <ul className="hl-limitations">
                  {data.limitations.map((l) => (
                    <li key={l}>{l}</li>
                  ))}
                </ul>
              </Panel>
            </>
          );
        }}
      </DataBoundary>
    </div>
  );
}
