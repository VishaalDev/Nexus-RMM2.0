import { usePolling } from "./usePolling";
import { getHealthReport, getRiskReport } from "../api/services/healthService";

/**
 * The backend caches each analysis for a few seconds and the underlying
 * telemetry changes slowly, so health is polled at the "slow" cadence.
 */
export const HEALTH_POLL_MS = 30_000;

export function useHealthReport() {
  return usePolling(getHealthReport, HEALTH_POLL_MS);
}

export function useRiskReport() {
  return usePolling(getRiskReport, HEALTH_POLL_MS);
}
