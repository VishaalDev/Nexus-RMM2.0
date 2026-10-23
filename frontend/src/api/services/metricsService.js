import { apiGet } from "../client";
import { ENDPOINTS } from "../endpoints";

export function getCurrentMetrics() {
  return apiGet(ENDPOINTS.metricsCurrent);
}

export function getMetricHistory(metric, window = "1h", instance = null) {
  return apiGet(ENDPOINTS.metricsHistory(metric, window, instance));
}
