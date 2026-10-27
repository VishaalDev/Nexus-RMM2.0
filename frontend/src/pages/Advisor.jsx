import DataBoundary from "../components/DataBoundary";
import HealthBadge from "../components/HealthBadge";
import IssueCard from "../components/IssueCard";
import PageHeader from "../components/PageHeader";
import Panel from "../components/Panel";
import Severity from "../components/Severity";
import StatStrip from "../components/StatStrip";
import { EmptyState } from "../components/States";
import { useAdvisor } from "../hooks/useAdvisor";
import { useAlerts } from "../hooks/useAdvisorData";
import { formatCompactDateTime, formatTime } from "../utils/format";
import { SEVERITY_META, formatPercent, formatScore } from "../utils/health";
import { ALERT_TYPE_META, categoryLabel } from "../utils/advisor";
import { statusTone } from "../utils/overview";
import "../styles/health.css";
import "../styles/advisor.css";

function Counts({ report }) {
  return (
    <ul className="adv-counts" aria-label="Issue counts">
      {["CRITICAL", "WARNING", "INFO"].map((sev) => {
        const n = { CRITICAL: report.criticalCount, WARNING: report.warningCount, INFO: report.infoCount }[sev];
        const meta = SEVERITY_META[sev];
        return (
          <li key={sev} className={n === 0 ? "adv-count--zero" : undefined}>
            <span className="badge-dot" aria-hidden="true" style={{ background: n > 0 ? meta.color : undefined }} />
            <strong>{n}</strong> {sev === "INFO" ? (n === 1 ? "note" : "notes") : meta.label.toLowerCase()}
          </li>
        );
      })}
    </ul>
  );
}

function Summary({ report }) {
  const tone = statusTone(report);
  return (
    <section className="panel adv-summary" aria-label="Summary">
      <div className="adv-summary-top">
        <p className="adv-headline">
          <span className={`tone-dot tone-dot--${tone}`} aria-hidden="true" />
          {report.headline}
        </p>
        <Counts report={report} />
      </div>
      <StatStrip
        label="Health summary"
        items={[
          {
            label: "Health score",
            value: (
              <>
                {formatScore(report.healthScore)} <HealthBadge value={report.healthStatus} />
              </>
            ),
            sub: `${formatPercent(report.healthCoverage)} of the score could be assessed`,
          },
          {
            label: "Failure risk",
            value: <HealthBadge kind="risk" value={report.riskLevel} confidence="MEDIUM" />,
            sub: <a href="#/risk">Risk details</a>,
          },
          {
            label: "Program-level detail",
            value: report.processInsight ? "Available" : "Unavailable",
            sub: report.processInsight ? "Issues can name the programs involved." : "Issues cannot name programs right now.",
          },
        ]}
      />
    </section>
  );
}

function TopActions({ actions }) {
  if (actions.length === 0) return null;
  return (
    <Panel title="Do this first">
      <ol className="todo">
        {actions.map((a, i) => (
          <li key={`${a.issueKey}-${a.recommendation.code}`}>
            <span className="todo-number" aria-hidden="true">
              {i + 1}
            </span>
            <div>
              <div className="todo-action">{a.recommendation.action}</div>
              <div className="todo-issue">
                <Severity value={a.severity} />
                <span className="muted">{a.issueTitle}</span>
              </div>
            </div>
          </li>
        ))}
      </ol>
    </Panel>
  );
}

function AlertLog() {
  const alerts = useAlerts(50);
  return (
    <Panel title="Recent alerts" actions="Kept in memory only" flush>
      {alerts.error && !alerts.data ? (
        <p className="state state-error" role="alert">
          Could not load alerts. {alerts.error.message}
        </p>
      ) : !alerts.data ? (
        <p className="state" role="status">
          Loading alerts&hellip;
        </p>
      ) : alerts.data.length === 0 ? (
        <EmptyState>No alerts since the backend started.</EmptyState>
      ) : (
        <ul className="alert-log">
          {alerts.data.map((a, i) => (
            <li key={`${a.time}-${a.issueKey}-${a.type}-${i}`}>
              <span className="alert-time muted">{formatCompactDateTime(a.time)}</span>
              <span className={`alert-type alert-type--${a.type.toLowerCase()}`}>{ALERT_TYPE_META[a.type] ?? a.type}</span>
              <Severity value={a.severity} />
              <span className="alert-what">
                {a.title} <span className="muted">{categoryLabel(a.category)}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

function Limitations({ items }) {
  return (
    <Panel title="Scope">
      <details className="disclosure">
        <summary>
          What the Advisor cannot see<span className="disclosure-hint">{items.length}</span>
        </summary>
        <ul className="hl-limitations">
          {items.map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
      </details>
    </Panel>
  );
}

export default function Advisor() {
  const advisor = useAdvisor();
  const generated = advisor.data?.generatedAt;

  return (
    <div className="page">
      <PageHeader
        title="Advisor"
        subtitle="Prioritized issues with concrete steps. Nexus-RMM only suggests; it never changes anything on this computer."
        meta={generated ? <span>Updated {formatTime(generated)} &middot; every 20 s</span> : null}
      />

      <DataBoundary state={advisor} what="advisor report">
        {(report) => (
          <div className="adv-layout">
            <div className="adv-area-summary">
              <Summary report={report} />
            </div>

            <div className="adv-area-todo">
              <TopActions actions={report.topActions} />
            </div>

            <div className="adv-area-issues">
              {report.issues.length === 0 ? (
                <Panel title="Issues">
                  <EmptyState>Nothing needs attention right now. See the summary for how much could be assessed.</EmptyState>
                </Panel>
              ) : (
                <section aria-label="Issues" className="issue-list">
                  {report.issues.map((issue) => (
                    <IssueCard key={issue.key} issue={issue} nowMs={Date.parse(report.generatedAt)} />
                  ))}
                </section>
              )}
            </div>

            <div className="adv-area-alerts">
              <AlertLog />
            </div>

            <div className="adv-area-scope">
              <Limitations items={report.limitations} />
            </div>
          </div>
        )}
      </DataBoundary>
    </div>
  );
}
