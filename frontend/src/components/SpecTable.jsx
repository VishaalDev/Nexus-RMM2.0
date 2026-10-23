/** Label/value specification rows. rows: [[label, value, mono?]]; a missing value reads as "Unavailable". */
export default function SpecTable({ rows, caption }) {
  return (
    <table className="data-table spec-table">
      {caption && <caption className="sr-only">{caption}</caption>}
      <tbody>
        {rows.map(([label, value, mono]) => {
          const missing = value === null || value === undefined || value === "";
          return (
            <tr key={label}>
              <th scope="row">{label}</th>
              <td className={mono && !missing ? "mono" : undefined}>
                {missing ? <span className="muted">Unavailable</span> : value}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
