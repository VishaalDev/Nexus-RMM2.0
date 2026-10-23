import { apiGet } from "../client";
import { ENDPOINTS } from "../endpoints";

export function getDiagnostics() {
  return apiGet(ENDPOINTS.diagnostics);
}
