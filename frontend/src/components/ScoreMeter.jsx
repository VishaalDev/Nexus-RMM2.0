import { formatScore } from "../utils/health";

/**
 * A 0-100 meter with the number always shown beside it. When there is no
 * score the bar is empty and the number is an em dash - an unassessed
 * component must never look like a zero or a full bar.
 */
export default function ScoreMeter({ score, label, color }) {
  const hasScore = score !== null && score !== undefined;
  return (
    <div className="hl-meter">
      <div
        className="hl-meter-track"
        role="meter"
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={hasScore ? score : undefined}
        aria-valuetext={hasScore ? `${score} out of 100` : "No score"}
      >
        {hasScore && <div className="hl-meter-fill" style={{ width: `${score}%`, background: color }} />}
      </div>
      <span className="hl-meter-value">{formatScore(score)}</span>
    </div>
  );
}
