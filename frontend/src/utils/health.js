/**
 * Presentation helpers for the Health and Risk pages. Scores and levels are
 * computed by the backend; nothing here derives or adjusts them.
 */

const SUCCESS = "var(--color-success)";
const ACCENT = "var(--color-accent)";
const WARNING = "var(--color-warning)";
const CRITICAL = "var(--color-critical)";
const MUTED = "var(--color-text-muted)";

export const STATUS_META = {
  HEALTHY: { label: "Healthy", color: SUCCESS },
  FAIR: { label: "Fair", color: ACCENT },
  DEGRADED: { label: "Degraded", color: WARNING },
  CRITICAL: { label: "Critical", color: CRITICAL },
  NO_DATA: { label: "No data", color: MUTED },
};

export const RISK_META = {
  LOW: { label: "Low risk", color: SUCCESS },
  MODERATE: { label: "Moderate risk", color: ACCENT },
  HIGH: { label: "High risk", color: WARNING },
  CRITICAL: { label: "Critical risk", color: CRITICAL },
  NOT_ASSESSED: { label: "Not assessed", color: MUTED },
};

export const SEVERITY_META = {
  CRITICAL: { label: "Critical", color: CRITICAL, rank: 0 },
  WARNING: { label: "Warning", color: WARNING, rank: 1 },
  INFO: { label: "Info", color: MUTED, rank: 2 },
};

/** A missing score is "no score", never 0 and never blank. */
export function formatScore(score) {
  return score === null || score === undefined ? "\u2014" : String(score);
}

export function formatPercent(fraction) {
  if (fraction === null || fraction === undefined || Number.isNaN(fraction)) return "\u2014";
  return `${Math.round(fraction * 100)}%`;
}

export function formatHorizonDays(days) {
  if (days === null || days === undefined || Number.isNaN(days)) return null;
  if (days < 1) return "less than 1 day";
  return `about ${days.toFixed(1)} days`;
}

/** Most severe first; stable for equal severities. */
export function sortFindings(findings) {
  return [...(findings ?? [])].sort(
    (a, b) => (SEVERITY_META[a.severity]?.rank ?? 9) - (SEVERITY_META[b.severity]?.rank ?? 9),
  );
}

export function confidenceLabel(confidence) {
  if (!confidence) return "Unknown";
  return confidence.charAt(0) + confidence.slice(1).toLowerCase();
}

/** Components that produced a score, for the "based on N of M" statement. */
export function countScored(components) {
  const list = components ?? [];
  return { scored: list.filter((c) => c.score !== null && c.score !== undefined).length, total: list.length };
}

// ---------------------------------------------------------------------------
// Risk presentation (derived ONLY from fields the backend returns)
// ---------------------------------------------------------------------------

export const CONFIDENCE_META = {
  HIGH: { label: "High", color: SUCCESS },
  MEDIUM: { label: "Medium", color: ACCENT },
  LOW: { label: "Low", color: WARNING },
};

/**
 * How a component's (or the overall) risk result should be shown. The
 * backend's calculation is not touched; this only decides wording/colour.
 *
 *  - not-assessed:     level NOT_ASSESSED. The backend had no usable evidence.
 *  - limited-evidence: level LOW but evidence confidence is not MEDIUM/HIGH.
 *                      The rules ran and none fired, but the backend's own
 *                      confidence says the history is too thin for "no
 *                      indicators" to mean "safe". Never shown as green.
 *  - assessed:         anything else. A genuine Low risk (confidence Medium or
 *                      High) keeps its green "Low risk"; Moderate/High/Critical
 *                      are positive evidence of a condition and are shown as is.
 */
export function riskPresentation(level, confidence) {
  if (level === "NOT_ASSESSED") {
    return { kind: "not-assessed", label: RISK_META.NOT_ASSESSED.label, color: MUTED };
  }
  if (level === "LOW" && confidence !== "MEDIUM" && confidence !== "HIGH") {
    return { kind: "limited-evidence", label: "No indicators \u00b7 limited evidence", color: MUTED };
  }
  const meta = RISK_META[level];
  return { kind: "assessed", label: meta?.label ?? String(level ?? "Unknown"), color: meta?.color ?? MUTED };
}

/**
 * Meaning of each `evidence` key a backend risk factor can carry (see
 * RiskEngine). Percent-valued keys are already 0-100 in the backend. Unknown
 * keys are shown under their raw name - never hidden, never guessed.
 */
const EVIDENCE_LABELS = {
  fractionPercent: { label: "Share of samples in the window", suffix: "%" },
  saturationPercent: { label: "Share of samples saturated", suffix: "%" },
  dwellPercent: { label: "Share of time at low charge", suffix: "%" },
  utilization: { label: "Utilization", suffix: "%" },
  average: { label: "Average", suffix: "%" },
  daysToFull: { label: "Projected days to full", suffix: " days" },
  slopePerDay: { label: "Trend per day", suffix: "" },
  count: { label: "Events in window", suffix: "" },
  last24h: { label: "Events in last 24 h", suffix: "" },
  dailyBaseline: { label: "Earlier daily event rate", suffix: "/day" },
  instance: { label: "Instance", suffix: "" },
};

/** Factor-specific wording for the generic `fractionPercent` key. */
const FRACTION_LABEL_BY_FACTOR = {
  SUSTAINED_HEAT: "Samples above the warning temperature",
  CRITICAL_TEMPERATURE: "Samples at or above the critical temperature",
};

export function formatEvidence(factorCode, evidence) {
  return Object.entries(evidence ?? {}).map(([key, raw]) => {
    const meta = EVIDENCE_LABELS[key];
    const label =
      key === "fractionPercent" && FRACTION_LABEL_BY_FACTOR[factorCode]
        ? FRACTION_LABEL_BY_FACTOR[factorCode]
        : (meta?.label ?? key);
    const number = typeof raw === "number" ? String(Math.round(raw * 10) / 10) : String(raw);
    return { key, label, value: `${number}${typeof raw === "number" ? (meta?.suffix ?? "") : ""}` };
  });
}

/** Factor severity is a 0..1 rule-based strength; shown as 0..100. Not a probability. */
export function severityPoints(severity) {
  return Math.round((severity ?? 0) * 100);
}

/** One-sentence reading of an evidence-confidence level, from the backend's documented rules. */
export const CONFIDENCE_EXPLANATION = {
  HIGH: "Built on at least 7 days of well-covered history.",
  MEDIUM: "Built on at least 24 hours of reasonably covered history, but less than a full week.",
  LOW: "Built on short, sparse or low-coverage history. Treat this as indicative only.",
};

/** Telemetry coverage averaged over the components that were scored (each is valid samples vs expected). */
export function averageTelemetryCoverage(components) {
  const scored = (components ?? []).filter((c) => c.score !== null && c.score !== undefined);
  if (scored.length === 0) return null;
  return scored.reduce((sum, c) => sum + (c.dataCoverage ?? 0), 0) / scored.length;
}
