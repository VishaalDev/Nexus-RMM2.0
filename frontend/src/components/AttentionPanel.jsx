import HealthBadge from "./HealthBadge";
import Panel from "./Panel";
import Severity from "./Severity";
import { useAdvisor } from "../hooks/useAdvisor";
import { categoryLabel } from "../utils/advisor";
import { formatPercent, formatScore } from "../utils/health";
import { statusTone } from "../utils/overview";
import "../styles/advisor.css";

const SHOWN = 3;

/**
 * Overview status: one headline, the top issues each with its first step, and
 * the health/risk figures. Renders nothing when there is no advisor data
 * (loading, error, or no provider) so the Overview never shows a misleading
 * "all clear" on missing data; the Advisor page has the full error states.
 */
export default function AttentionPanel() {
  const { data } = useAdvisor();
  if (!data) return null;
  const urgent = data.issues.filter((i) => i.severity !== "INFO");
  const tone = statusTone(data);

  return (
    <Panel title="Status" actions={<a className="link-arrow" href="#/advisor">Open Advisor</a>}>
      <div className="status">
        <div className="status-main">
          <p className="status-headline">
            <span className={`tone-dot tone-dot--${tone}`} aria-hidden="true" />
            {data.headline}
          </p>
          {urgent.length > 0 && (
            <ul className="status-issues">
              {urgent.slice(0, SHOWN).map((i) => (
                <li key={i.key}>
                  <Severity value={i.severity} />
                  <div>
                    <div className="status-issue-title">
                      <span>{i.title}</span>
                      <span className="muted">{categoryLabel(i.category)}</span>
                    </div>
                    {i.recommendations?.[0] && <div className="status-issue-action">{i.recommendations[0].action}</div>}
                  </div>
                </li>
              ))}
              {urgent.length > SHOWN && <li className="muted status-more">and {urgent.length - SHOWN} more</li>}
            </ul>
          )}
        </div>
        <dl className="status-figures">
          <div>
            <dt>Health score</dt>
            <dd className="stat-value">
              {formatScore(data.healthScore)}
              <span className="stat-value-badge">
                <HealthBadge value={data.healthStatus} />
              </span>
            </dd>
            <dd className="stat-sub">{formatPercent(data.healthCoverage)} assessed</dd>
          </div>
          <div>
            <dt>Failure risk</dt>
            <dd className="stat-value">
              <HealthBadge kind="risk" value={data.riskLevel} confidence="MEDIUM" />
            </dd>
            <dd className="stat-sub">
              <a href="#/risk">Risk details</a>
            </dd>
          </div>
        </dl>
      </div>
    </Panel>
  );
}
