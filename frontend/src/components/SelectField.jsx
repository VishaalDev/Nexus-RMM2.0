import { useId } from "react";

/** options: [{ value, label }] */
export default function SelectField({ label, value, options, onChange, disabled }) {
  const id = useId();
  return (
    <label className="field" htmlFor={id}>
      {label}
      <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}
