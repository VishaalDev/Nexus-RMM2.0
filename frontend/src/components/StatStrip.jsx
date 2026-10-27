/**
 * A row of labelled values. items: [{ label, value, sub?, key? }].
 * Rendered as a description list so each label stays tied to its value.
 */
export default function StatStrip({ items, label, flat = false }) {
  return (
    <dl className={`stat-strip${flat ? " stat-strip--flat" : ""}`} aria-label={label}>
      {items.map((item) => (
        <div key={item.key ?? item.label}>
          <dt>{item.label}</dt>
          <dd className="stat-value">{item.value}</dd>
          {item.sub && <dd className="stat-sub">{item.sub}</dd>}
        </div>
      ))}
    </dl>
  );
}
