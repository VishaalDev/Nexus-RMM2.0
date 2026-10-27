import { getAdvisorReport } from "../api/services/advisorService";
import { AdvisorContext } from "./useAdvisor";
import { ADVISOR_POLL_MS } from "./useAdvisorData";
import { usePolling } from "./usePolling";

/**
 * One poller for the advisor report, shared by the sidebar badge, the
 * Overview "needs attention" panel and the Advisor page, so three consumers
 * never mean three requests.
 */
export function AdvisorProvider({ children }) {
  const state = usePolling(getAdvisorReport, ADVISOR_POLL_MS);
  return <AdvisorContext.Provider value={state}>{children}</AdvisorContext.Provider>;
}
