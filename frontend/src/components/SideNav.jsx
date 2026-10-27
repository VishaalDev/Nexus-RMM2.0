/** items: [{ id, label, group?, badge?, badgeLevel? }]. Items sharing a `group` render under one small heading. */
export default function SideNav({ items, active, onSelect }) {
  const groups = [];
  for (const item of items) {
    const name = item.group ?? "";
    const last = groups[groups.length - 1];
    if (last && last.name === name) last.items.push(item);
    else groups.push({ name, items: [item] });
  }

  return (
    <nav className="nav" aria-label="Primary">
      {groups.map((g) => (
        <div className="nav-group" key={g.name || "main"}>
          {g.name && <div className="nav-group-label">{g.name}</div>}
          {g.items.map((item) => (
            <button
              key={item.id}
              type="button"
              className="nav-item"
              aria-current={active === item.id ? "page" : undefined}
              onClick={() => onSelect(item.id)}
            >
              <span>{item.label}</span>
              {item.badge ? (
                <>
                  <span className={`nav-badge${item.badgeLevel === "warning" ? " nav-badge--warning" : ""}`} aria-hidden="true">
                    {item.badge}
                  </span>
                  <span className="sr-only"> ({item.badge} need attention)</span>
                </>
              ) : null}
            </button>
          ))}
        </div>
      ))}
    </nav>
  );
}
