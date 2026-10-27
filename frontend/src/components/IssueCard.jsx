import Severity from "./Severity";
import { SEVERITY_META } from "../utils/health";
import { EFFORT_META, categoryLabel, evidenceEntries, openFor } from "../utils/advisor";

function Effort({ effort }) {
  const meta = EFFORT_META[effort];
  return (
    <span className="chip" title={meta?.hint}>
      Effort <strong>{meta?.label ?? effort}</strong>
    </span>
  );
}

function Step({ rec }) {
  return (
    <li className="step">
      <div className="step-action">{rec.action}</div>
      {rec.why && <p className="step-why">{rec.why}</p>}
      <p className="step-meta">
        <Effort effort={rec.effort} />
        {rec.expectedBenefit && <span className="muted">{rec.expectedBenefit}</span>}
      </p>
      {rec.caution && (
        <p className="hl-caution" role="note">
          <strong>Careful:</strong> {rec.caution}
        </p>
      )}
    </li>
  );
}

/**
 * One issue, ranked by the backend. At a glance: what is wrong, how long it has
 * been open, and the single most useful action (with its safety caution, which
 * is never hidden). Everything else (why, the remaining steps, the raw
 * evidence) sits in a native disclosure, so it costs nothing until opened.
 *
 * `nowMs` is the report's own generation time, so ages are not skewed by the browser clock.
 */
export default function IssueCard({ issue, nowMs }) {
  const meta = SEVERITY_META[issue.severity];
  const age = openFor(issue.firstSeen, nowMs);
  const evidence = evidenceEntries(issue.evidence);
  const steps = issue.recommendations ?? [];
  const [primary, ...rest] = steps;
  const attribution = issue.evidence?.attribution;
  const hasDetails = rest.length > 0 || evidence.length > 0 || Boolean(primary?.why || primary?.expectedBenefit);

  return (
    <article
      className={`issue issue--${String(issue.severity).toLowerCase()}`}
      aria-label={`${meta?.label ?? issue.severity}: ${issue.title}`}
    >
      <header className="issue-head">
        <h4 className="issue-title">{issue.title}</h4>
        <span className="issue-tags">
          <Severity value={issue.severity} />
          <span className="muted">{categoryLabel(issue.category)}</span>
          {age && <span className="muted">{age}</span>}
        </span>
      </header>

      <p className="issue-summary">{issue.summary}</p>
      {attribution && <p className="issue-summary muted">{attribution}</p>}

      {primary && (
        <div className="issue-action">
          <span className="issue-action-label">Do this</span>
          <span className="issue-action-text">{primary.action}</span>
          <Effort effort={primary.effort} />
        </div>
      )}
      {primary?.caution && (
        <p className="hl-caution" role="note">
          <strong>Careful:</strong> {primary.caution}
        </p>
      )}

      {hasDetails && (
        <details className="disclosure issue-details">
          <summary>
            Details
            <span className="disclosure-hint">
              {[rest.length > 0 && `${rest.length} more ${rest.length === 1 ? "step" : "steps"}`, evidence.length > 0 && "evidence"]
                .filter(Boolean)
                .join(" \u00B7 ")}
            </span>
          </summary>

          {(primary?.why || primary?.expectedBenefit) && (
            <p className="issue-why">
              <strong>Why: </strong>
              {[primary.why, primary.expectedBenefit].filter(Boolean).join(" ")}
            </p>
          )}

          {rest.length > 0 && (
            <>
              <h5 className="issue-subhead">Then</h5>
              <ol className="steps" start={2}>
                {rest.map((rec) => (
                  <Step key={rec.code} rec={rec} />
                ))}
              </ol>
            </>
          )}

          {evidence.length > 0 && (
            <>
              <h5 className="issue-subhead">Evidence</h5>
              <dl className="hl-evidence">
                {evidence.map((e) => (
                  <div key={e.key}>
                    <dt>{e.label}</dt>
                    <dd>{e.value}</dd>
                  </div>
                ))}
              </dl>
            </>
          )}
        </details>
      )}
    </article>
  );
}
