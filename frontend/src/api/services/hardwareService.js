import { apiGet } from "../client";
import { ENDPOINTS } from "../endpoints";

export function getHardwareSnapshot() {
  return apiGet(ENDPOINTS.hardware);
}
