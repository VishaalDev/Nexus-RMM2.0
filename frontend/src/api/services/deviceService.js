import { apiGet } from "../client";
import { ENDPOINTS } from "../endpoints";

export function getDevice() {
  return apiGet(ENDPOINTS.device);
}

export function getBackendHealth() {
  return apiGet(ENDPOINTS.health);
}
