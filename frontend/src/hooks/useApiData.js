import { usePolling } from "./usePolling";
import { getBackendHealth, getDevice } from "../api/services/deviceService";
import { getCurrentMetrics, getMetricHistory } from "../api/services/metricsService";
import { getHardwareSnapshot } from "../api/services/hardwareService";
import { getEvents } from "../api/services/eventsService";
import { getDiagnostics } from "../api/services/diagnosticsService";

/**
 * One thin hook per backend resource. Every page reads data through these,
 * so polling cadence and request wiring live in exactly one place.
 */

/** Matches the backend's default collection interval (10 s). */
export const LIVE_POLL_MS = 10_000;
/** Slower cadence for data that changes rarely (capacity, adapters, events). */
export const SLOW_POLL_MS = 30_000;

export function useBackendHealth() {
  return usePolling(getBackendHealth, LIVE_POLL_MS);
}

export function useDevice() {
  return usePolling(getDevice, SLOW_POLL_MS);
}

export function useHardwareSnapshot() {
  return usePolling(getHardwareSnapshot, SLOW_POLL_MS);
}

export function useCurrentMetrics() {
  return usePolling(getCurrentMetrics, LIVE_POLL_MS);
}

export function useMetricHistory(metric, instance, window) {
  return usePolling(() => getMetricHistory(metric, window, instance), LIVE_POLL_MS, [
    metric,
    instance,
    window,
  ]);
}

export function useDiagnostics() {
  return usePolling(getDiagnostics, SLOW_POLL_MS);
}

export function useEvents(limit) {
  return usePolling(() => getEvents(limit), SLOW_POLL_MS, [limit]);
}
