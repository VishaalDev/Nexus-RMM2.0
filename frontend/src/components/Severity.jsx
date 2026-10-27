import { SEVERITY_META } from "../utils/health";

/** Severity as a dot plus text, never colour alone. */
export default function Severity({ value }) {
  const meta = SEVERITY_META[value];
  return <span className={`sev sev--${String(value).toLowerCase()}`}>{meta?.label ?? String(value)}</span>;
}
