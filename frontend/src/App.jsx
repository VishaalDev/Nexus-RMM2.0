import { BackendOffline } from "./components/States";
import { AdvisorProvider } from "./hooks/AdvisorProvider";
import { useAdvisor } from "./hooks/useAdvisor";
import { attentionCount } from "./utils/advisor";
import SideNav from "./components/SideNav";
import { useBackendHealth } from "./hooks/useApiData";
import { useHashRoute } from "./hooks/useHashRoute";
import Advisor from "./pages/Advisor";
import Diagnostics from "./pages/Diagnostics";
import Events from "./pages/Events";
import Hardware from "./pages/Hardware";
import Health from "./pages/Health";
import History from "./pages/History";
import Network from "./pages/Network";
import Overview from "./pages/Overview";
import Processes from "./pages/Processes";
import Risk from "./pages/Risk";
import Storage from "./pages/Storage";
import Sync from "./pages/Sync";

/** Grouped by task: what is happening now, how healthy it is, what is installed, what happened, and the app itself. */
const NAV_ITEMS = [
  { id: "overview", label: "Overview", group: "Monitor", Component: Overview },
  { id: "advisor", label: "Advisor", group: "Monitor", Component: Advisor },
  { id: "processes", label: "Processes", group: "Monitor", Component: Processes },
  { id: "health", label: "Health", group: "Analysis", Component: Health },
  { id: "risk", label: "Risk", group: "Analysis", Component: Risk },
  { id: "storage", label: "Storage", group: "Inventory", Component: Storage },
  { id: "hardware", label: "Hardware", group: "Inventory", Component: Hardware },
  { id: "network", label: "Network", group: "Inventory", Component: Network },
  { id: "history", label: "History", group: "Records", Component: History },
  { id: "events", label: "Events", group: "Records", Component: Events },
  { id: "sync", label: "Sync", group: "System", Component: Sync },
  { id: "diagnostics", label: "Diagnostics", group: "System", Component: Diagnostics },
];

/** The shell, inside AdvisorProvider so the nav badge shares the Advisor page's poller. */
function Shell({ route, navigate }) {
  const advisor = useAdvisor();
  const attention = attentionCount(advisor.data);
  const items = NAV_ITEMS.map((i) =>
    i.id === "advisor" && attention > 0
      ? { ...i, badge: attention, badgeLevel: advisor.data.criticalCount > 0 ? "critical" : "warning" }
      : i,
  );
  const ActivePage = NAV_ITEMS.find((i) => i.id === route)?.Component ?? Overview;

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            N
          </span>
          <span>Nexus-RMM</span>
        </div>
        <SideNav items={items} active={route} onSelect={navigate} />
        <div className="sidebar-footer">
          <span className="conn-dot" aria-hidden="true" />
          Backend connected
        </div>
      </aside>
      <main className="content">
        <ActivePage />
      </main>
    </div>
  );
}

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

  return (
    <AdvisorProvider>
      <Shell route={route} navigate={navigate} />
    </AdvisorProvider>
  );
}
