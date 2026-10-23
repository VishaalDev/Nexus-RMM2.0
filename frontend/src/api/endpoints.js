export const ENDPOINTS = {
  health: "/api/health",
  device: "/api/device",
  hardware: "/api/device/hardware",
  metricsCurrent: "/api/metrics/current",
  /**
   * `metric` is required by the backend (see docs/api.md). `instance` is
   * only meaningful for multi-instance metrics (volumes, disks, adapters).
   */
  metricsHistory: (metric, window, instance) => {
    const params = [
      `metric=${encodeURIComponent(metric)}`,
      `window=${encodeURIComponent(window)}`,
    ];
    if (instance) params.push(`instance=${encodeURIComponent(instance)}`);
    return `/api/metrics/history?${params.join("&")}`;
  },
  events: (limit) => `/api/events?limit=${encodeURIComponent(limit)}`,
  diagnostics: "/api/diagnostics",
};
