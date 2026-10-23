import { apiGet } from "../client";
import { ENDPOINTS } from "../endpoints";

export function getEvents(limit = 100) {
  return apiGet(ENDPOINTS.events(limit));
}
