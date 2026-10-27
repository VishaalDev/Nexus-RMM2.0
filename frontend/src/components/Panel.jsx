/**
 * The one container used across the app: a hairline-bordered surface with an
 * optional header row. `flush` removes body padding for tables that should
 * run edge to edge.
 */
export default function Panel({ title, actions, children, className = "", flush = false }) {
  const classes = ["panel", flush ? "panel--flush" : "", className].filter(Boolean).join(" ");
  return (
    <section className={classes}>
      {(title || actions) && (
        <div className="panel-header">
          {title ? <h3 className="panel-title">{title}</h3> : <span />}
          {actions && <div className="panel-actions">{actions}</div>}
        </div>
      )}
      <div className="panel-body">{children}</div>
    </section>
  );
}
