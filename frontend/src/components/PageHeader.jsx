/** Page title with an optional one-line subtitle and right-aligned meta (timestamps, counts). */
export default function PageHeader({ title, subtitle, meta }) {
  return (
    <header className="page-header">
      <div>
        <h2>{title}</h2>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {meta && <div className="page-meta">{meta}</div>}
    </header>
  );
}
