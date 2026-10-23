import { apiGet } from "../client";
import { HEALTH_ENDPOINTS } from "../healthEndpoints";

export function getHealthReport() {
  return apiGet(HEALTH_ENDPOINTS.report);
}

export function getRiskReport() {
  return apiGet(HEALTH_ENDPOINTS.risk);
}
