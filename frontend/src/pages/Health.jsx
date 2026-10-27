import ComponentHealthCard from "../components/ComponentHealthCard";
import DataBoundary from "../components/DataBoundary";
import HealthBadge from "../components/HealthBadge";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import ScoreMeter from "../components/ScoreMeter";
import StatStrip from "../components/StatStrip";
import { useHealthReport } from "../hooks/useHealthData";
import { formatTime } from "../utils/format";
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
      <StatStrip
        label="Coverage of this score"
        items={[
          {
            label: "Assessed components",
            value: (
              <>
                {scored} of {total}
              </>
            ),
          },
          {
            label: "Component-weight coverage",
            value: formatPercent(data.dataCoverage),
            sub: "Share of the score\u2019s total weight that had data.",
          },
          {
            label: "Telemetry coverage",
            value: telemetry === null ? "\u2014" : formatPercent(telemetry),
            sub: "Average over assessed components: valid samples in the last hour versus the number expected.",
          },
          {
            label: "Insufficient data",
            value: `${insufficient.length} component${insufficient.length === 1 ? "" : "s"}`,
            sub:
              insufficient.length > 0
                ? `${insufficient.map((c) => c.label).join(", ")}: not scored, excluded from the overall score, never counted as healthy.`
                : "Every component produced a score.",
          },
        ]}
      />
      {scored < total && (
        <p className="hl-caution hl-caution--panel" role="note">
          The overall score reflects only the {scored} assessed component{scored === 1 ? "" : "s"}. Components without
          data could raise or lower it once they can be assessed.
        </p>
      )}
    </>
  );
}

export default function Health() {
  const report = useHealthReport();
  return (
    <div className="page">
      <PageHeader
        title="Health"
        subtitle="Component scores computed from collected telemetry. Heuristics, not a diagnosis; “No data” is never counted as healthy."
        meta={report.data ? <span>Updated {formatTime(report.data.generatedAt)} &middot; every 30 s</span> : null}
      />

      <DataBoundary state={report} what="health analysis">
        {(data) => (
          <>
            <section className="panel" aria-label="Overall health">
              <div className="hl-overall">
                <div className="hl-overall-score">
                  <span className="hl-overall-label">Overall health</span>
                  <ScoreMeter
                    size="lg"
                    score={data.overallScore}
                    label="Overall health score"
                    color={STATUS_META[data.overallStatus]?.color}
                  />
                  <HealthBadge value={data.overallStatus} />
                </div>
                <p className="muted hl-overall-basis">{data.overallBasis}</p>
              </div>
              <CoverageSummary data={data} />
            </section>

            <div className="hl-grid">
              {data.components.map((c) => (
                <ComponentHealthCard key={c.component} component={c} />
              ))}
            </div>

            <Panel title="Scope">
              <details className="disclosure">
                <summary>
                  What this report cannot see<span className="disclosure-hint">{data.limitations.length}</span>
                </summary>
                <ul className="hl-limitations">
                  {data.limitations.map((l) => (
                    <li key={l}>{l}</li>
                  ))}
                </ul>
              </details>
            </Panel>
          </>
        )}
      </DataBoundary>
    </div>
  );
}
