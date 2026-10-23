import { BackendOffline } from "./components/States";
import SideNav from "./components/SideNav";
import { useBackendHealth } from "./hooks/useApiData";
import { useHashRoute } from "./hooks/useHashRoute";
import Diagnostics from "./pages/Diagnostics";
import Events from "./pages/Events";
import Hardware from "./pages/Hardware";
import Health from "./pages/Health";
import History from "./pages/History";
import Network from "./pages/Network";
import Overview from "./pages/Overview";
import Risk from "./pages/Risk";
import Storage from "./pages/Storage";
import Sync from "./pages/Sync";

const NAV_ITEMS = [
  { id: "overview", label: "Overview", Component: Overview },
  { id: "health", label: "Health", Component: Health },
  { id: "risk", label: "Risk", Component: Risk },
  { id: "storage", label: "Storage", Component: Storage },
  { id: "hardware", label: "Hardware", Component: Hardware },
  { id: "network", label: "Network", Component: Network },
  { id: "history", label: "History", Component: History },
  { id: "events", label: "Events", Component: Events },
  { id: "sync", label: "Sync", Component: Sync },
  { id: "diagnostics", label: "Diagnostics", Component: Diagnostics },
];

export default function App() {
  const health = useBackendHealth();
  const [route, navigate] = useHashRoute(
    NAV_ITEMS.map((i) => i.id),
    "overview",
  );

  // First paint, before the first health check resolves either way.
  if (health.loading && !health.data && !health.error) {
    return (
      <div className="state-centered">
        <p className="state">Connecting to backend&hellip;</p>
      </div>
    );
  }

  if (health.error) {
    return <BackendOffline />;
  }

  const ActivePage = NAV_ITEMS.find((i) => i.id === route)?.Component ?? Overview;

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <h1>Nexus-RMM</h1>
          <p>Lightweight Monitoring System with Offline Data Synchronization</p>
        </div>
        <SideNav items={NAV_ITEMS} active={route} onSelect={navigate} />
        <div className="sidebar-footer">Modules 1&ndash;3 &mdash; Monitoring, Health &amp; Sync</div>
      </aside>
      <main className="content">
        <ActivePage />
      </main>
    </div>
  );
}
