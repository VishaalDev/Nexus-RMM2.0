import { apiGet } from "../client";

export function getAdvisorReport() {
  return apiGet("/api/advisor");
}

export function getAlerts(limit = 50) {
  return apiGet(`/api/advisor/alerts?limit=${encodeURIComponent(limit)}`);
}

export function getProcesses() {
  return apiGet("/api/processes");
}
