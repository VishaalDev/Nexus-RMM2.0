import { usePolling } from "./usePolling";
import { getAlerts, getProcesses } from "../api/services/advisorService";

/** The backend evaluates the advisor every minute and caches reports for ~10 s. */
export const ADVISOR_POLL_MS = 20_000;
/** The process sampler runs every 30 s by default; polling faster only re-reads the same snapshot. */
export const PROCESS_POLL_MS = 30_000;
export const ALERTS_POLL_MS = 30_000;

export function useProcesses() {
  return usePolling(getProcesses, PROCESS_POLL_MS);
}

export function useAlerts(limit = 50) {
  return usePolling(() => getAlerts(limit), ALERTS_POLL_MS, [limit]);
}
