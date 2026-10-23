import { useEffect } from "react";
import { usePolling } from "./usePolling";
import { getSyncStatus } from "../api/services/syncService";

/** Idle cadence for the Sync page; matches how rarely queue state changes. */
export const SYNC_POLL_MS = 5_000;
/** Faster refresh while a run is in progress so the page shows real progress. */
export const SYNC_ACTIVE_POLL_MS = 1_000;

/**
 * Polls the real /api/sync/status. While the backend reports a run in
 * progress it re-fetches every second, so the page reflects the actual
 * outcome as soon as the backend finishes (never a guess).
 */
export function useSyncStatus() {
  const polling = usePolling(getSyncStatus, SYNC_POLL_MS);
  const running = polling.data?.running === true;
  const { refetch } = polling;

  useEffect(() => {
    if (!running) return undefined;
    const id = setInterval(refetch, SYNC_ACTIVE_POLL_MS);
    return () => clearInterval(id);
  }, [running, refetch]);

  return polling;
}
