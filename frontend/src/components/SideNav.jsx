export default function SideNav({ items, active, onSelect }) {
  return (
    <nav className="nav" aria-label="Primary">
      {items.map((item) => (
        <button
          key={item.id}
          type="button"
          className="nav-item"
          aria-current={active === item.id ? "page" : undefined}
          onClick={() => onSelect(item.id)}
        >
          {item.label}
        </button>
      ))}
    </nav>
  );
}
