import DataBoundary from "../components/DataBoundary";
import HealthBadge from "../components/HealthBadge";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import ScoreMeter from "../components/ScoreMeter";
import { useRiskReport } from "../hooks/useHealthData";
import { formatTime } from "../utils/format";
import {
  CONFIDENCE_EXPLANATION,
  CONFIDENCE_META,
  confidenceLabel,
  formatEvidence,
  formatHorizonDays,
  riskPresentation,
  severityPoints,
} from "../utils/health";
import "../styles/health.css";

/** Evidence confidence as text plus a dot (never colour alone). */
function ConfidenceChip({ confidence }) {
  const meta = CONFIDENCE_META[confidence];
  return (
    <span className="hl-chip">
      <span className="badge-dot" aria-hidden="true" style={{ background: meta?.color ?? "var(--color-text-muted)" }} />
      Evidence confidence <strong>{confidenceLabel(confidence)}</strong>
    </span>
  );
}

function FactorList({ factors }) {
  return (
    <ul className="hl-findings">
      {factors.map((f) => {
        const evidence = formatEvidence(f.code, f.evidence);
        return (
          <li key={`${f.code}-${f.description}`} className="hl-factor">
            <span
              className="hl-factor-severity"
              title="Factor severity: the strength of this one factor under the backend's rules, 0 to 100. Not a probability and not a share of samples."
            >
              <span className="hl-factor-label">Severity</span>
              <strong>{severityPoints(f.severity)}</strong>
              <span className="muted">/100</span>
            </span>
            <div>
              <div>{f.description}</div>
              {evidence.length > 0 && (
                <dl className="hl-evidence">
                  {evidence.map((e) => (
                    <div key={e.key}>
                      <dt>{e.label}</dt>
                      <dd>{e.value}</dd>
                    </div>
                  ))}
                </dl>
              )}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

function RiskRow({ risk }) {
  const horizon = formatHorizonDays(risk.horizonDays);
  const presentation = riskPresentation(risk.level, risk.confidence);

  return (
    <article className={`hl-card hl-card--${presentation.kind}`} aria-label={`${risk.label} risk`}>
      <header className="hl-card-header">
        <h4>{risk.label}</h4>
        <HealthBadge kind="risk" value={risk.level} confidence={risk.confidence} />
      </header>
      <ScoreMeter
        score={risk.riskScore}
        label={`${risk.label} risk index`}
        color={presentation.kind === "assessed" ? presentation.color : "var(--color-text-muted)"}
      />

      {presentation.kind === "not-assessed" ? (
        <p className="hl-no-data">{risk.notAssessedReason}</p>
      ) : (
        <>
          <ConfidenceChip confidence={risk.confidence} />
          {presentation.kind === "limited-evidence" && (
            <p className="hl-caution">
              No risk rule fired, but evidence confidence is {confidenceLabel(risk.confidence).toLowerCase()}. An index of{" "}
              {risk.riskScore ?? 0} here means &ldquo;nothing detected so far&rdquo;, not &ldquo;known to be safe&rdquo;.
            </p>
          )}
          {horizon && <p>Projected time to event: {horizon}</p>}
          {risk.factors.length === 0 ? (
            <p className="muted">No risk factors detected.</p>
          ) : (
            <FactorList factors={risk.factors} />
          )}
        </>
      )}
    </article>
  );
}

export default function Risk() {
  const report = useRiskReport();
  return (
    <div className="page">
      <PageHeader
        title="Failure risk"
        subtitle={
          <>
            A rule-based severity index for conditions that tend to precede problems. It is{" "}
            <strong>not a probability of failure</strong> and has not been calibrated against real failures.
          </>
        }
        meta={report.data ? <span>Updated {formatTime(report.data.generatedAt)} &middot; every 30 s</span> : null}
      />

      <DataBoundary state={report} what="risk analysis">
        {(data) => {
          const overall = riskPresentation(data.overallLevel, data.confidence);
          const overallConfidence = CONFIDENCE_META[data.confidence];
          return (
            <>
              <Panel title="Overall risk">
                <div className="hl-overall">
                  <div className="hl-overall-score">
                    <ScoreMeter
                      size="lg"
                      score={data.overallRiskScore}
                      label="Overall risk index"
                      color={overall.kind === "assessed" ? overall.color : "var(--color-text-muted)"}
                    />
                    <HealthBadge kind="risk" value={data.overallLevel} confidence={data.confidence} />
                    <p className="muted hl-coverage">Heuristic severity index (0&ndash;100), not a probability of hardware failure.</p>
                  </div>

                  <div className="hl-confidence" aria-label="Overall evidence confidence">
                    <span className="hl-confidence-title">Evidence confidence</span>
                    <span className="hl-confidence-value">
                      <span className="badge-dot" aria-hidden="true" style={{ background: overallConfidence?.color }} />
                      {confidenceLabel(data.confidence)}
                    </span>
                    <p className="muted">{CONFIDENCE_EXPLANATION[data.confidence] ?? ""}</p>
                  </div>
                </div>

                {data.confidence === "LOW" && (
                  <p className="hl-caution" role="note">
                    Low evidence confidence: this overall score rests on limited history. A High or Critical reading
                    still points to a condition that was actually observed; a Low or zero reading says only that
                    nothing has been detected yet.
                  </p>
                )}
                <details className="disclosure hl-method">
                  <summary>Methodology</summary>
                  <p className="muted">{data.methodology}</p>
                </details>
              </Panel>

              <details className="disclosure hl-callout-details">
                <summary>Why &ldquo;not assessed&rdquo; is not &ldquo;safe&rdquo;</summary>
                <p className="hl-callout" role="note">
                  <strong>Not assessed</strong> or <strong>limited-evidence</strong> results cannot establish that the
                  system is safe. They mean the backend lacked, or had too little, evidence to say either way. Only a{" "}
                  <strong>Low risk</strong> result with Medium or High evidence confidence is a genuine &ldquo;nothing
                  found&rdquo;.
                </p>
              </details>

              <div className="hl-grid">
                {data.components.map((c) => (
                  <RiskRow key={c.component} risk={c} />
                ))}
              </div>

              <Panel title="Reference">
                <details className="disclosure">
                  <summary>How to read these numbers</summary>
                  <dl className="hl-glossary">
                    <dt>Risk index (0&ndash;100)</dt>
                    <dd>
                      A heuristic severity index. Each component&rsquo;s factors combine as 1 &minus; &prod;(1 &minus;
                      severity), and components combine the same way after weighting. It is not a probability.
                    </dd>
                    <dt>Factor severity (0&ndash;100)</dt>
                    <dd>
                      How strongly one rule fired, from the backend&rsquo;s own scale. It is not a probability and not a
                      percentage of samples. Percentages that appear in a factor&rsquo;s description or in its evidence
                      list (for example &ldquo;share of samples above the warning temperature&rdquo;) are measured facts
                      about the data, separate from the severity.
                    </dd>
                    <dt>Evidence confidence</dt>
                    <dd>
                      How much history supports the result, not how risky the machine is. The backend rates it High with
                      at least 7 days of history and 60% data coverage, Medium with at least 24 hours and 30%, and Low
                      otherwise. History that is sparse (under 25% of its time span populated) is always Low.
                    </dd>
                    <dt>Low risk vs. limited evidence vs. not assessed</dt>
                    <dd>
                      <em>Low risk</em>: rules ran, none fired, and confidence is Medium or High.{" "}
                      <em>No indicators &middot; limited evidence</em>: rules ran and none fired, but confidence is Low.{" "}
                      <em>Not assessed</em>: there was no usable evidence, or the component yields no failure indicators
                      by design.
                    </dd>
                  </dl>
                </details>
              </Panel>
            </>
          );
        }}
      </DataBoundary>
    </div>
  );
}
