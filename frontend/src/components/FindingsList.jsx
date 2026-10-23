import { SEVERITY_META, sortFindings } from "../utils/health";

/** Findings, most severe first, each with its severity as text (not colour alone). */
export default function FindingsList({ findings }) {
  const sorted = sortFindings(findings);
  if (sorted.length === 0) return null;
  return (
    <ul className="hl-findings">
      {sorted.map((f, i) => {
        const meta = SEVERITY_META[f.severity];
        return (
          <li key={`${f.code}-${i}`} className="hl-finding">
            <span className="hl-finding-severity" style={{ color: meta?.color }}>
              {meta?.label ?? f.severity}
            </span>
            <span>{f.message}</span>
          </li>
        );
      })}
    </ul>
  );
}
