import { STATUS_META, riskPresentation } from "../utils/health";

/**
 * Status / risk-level pill. Reuses the existing `.badge` styles; the text
 * label is always rendered so colour is never the only signal.
 *
 * `kind="risk"` goes through riskPresentation(), so a "Low" result whose
 * evidence confidence is Low is NOT shown as a green "Low risk" - pass the
 * result's `confidence` for that. (Without it, Low is treated as limited
 * evidence: absence of confidence is never read as safety.)
 */
export default function HealthBadge({ value, kind = "status", confidence }) {
  let color;
  let text;
  if (kind === "risk") {
    const p = riskPresentation(value, confidence);
    color = p.color;
    text = p.label;
  } else {
    const meta = STATUS_META[value];
    color = meta?.color;
    text = meta?.label ?? String(value ?? "Unknown");
  }
  return (
    <span className="badge">
      <span className="badge-dot" aria-hidden="true" style={{ background: color ?? "var(--color-text-muted)" }} />
      <span>{text}</span>
    </span>
  );
}
