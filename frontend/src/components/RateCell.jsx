import { formatRate } from "../utils/format";

/** A throughput reading: the real rate, or an honest N/A with the backend's reason. */
export default function RateCell({ reading, missingTitle }) {
  if (reading?.availabilityStatus === "AVAILABLE") {
    return <span className="mono">{formatRate(reading.value)}</span>;
  }
  if (reading) {
    return (
      <div>
        <span className="muted">N/A</span>
        {reading.diagnostic && <div className="note">{reading.diagnostic}</div>}
      </div>
    );
  }
  return (
    <span className="muted" title={missingTitle}>
      &mdash;
    </span>
  );
}
