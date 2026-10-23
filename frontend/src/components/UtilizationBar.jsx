/** level: "normal" | "elevated" | "high" | "neutral" (neutral = no good/bad meaning, e.g. battery charge). */
export default function UtilizationBar({ value, level = "normal", label }) {
  const pct = Math.max(0, Math.min(100, value));
  const fillClass = level === "normal" ? "bar-fill" : `bar-fill bar-${level}`;

  return (
    <div
      className="bar"
      role="meter"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={Math.round(pct)}
    >
      <div className={fillClass} style={{ width: `${pct}%` }} />
    </div>
  );
}
