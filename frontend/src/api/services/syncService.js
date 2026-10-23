import { apiGet, apiPost } from "../client";
import { SYNC_ENDPOINTS } from "../syncEndpoints";

export function getSyncStatus() {
  return apiGet(SYNC_ENDPOINTS.status);
}

/** Starts a real synchronization run on the backend. Resolves with { outcome, message, status }. */
export function triggerSync() {
  return apiPost(SYNC_ENDPOINTS.trigger);
}
