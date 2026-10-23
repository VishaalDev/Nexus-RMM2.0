/**
 * Module 2 (health analysis & failure risk) endpoints. Kept in their own
 * file so Module 1's endpoints.js is left untouched. Note that
 * `/api/health` (backend liveness, Module 1) is a different resource from
 * `/api/health/report` (health analysis, Module 2).
 */
export const HEALTH_ENDPOINTS = {
  report: "/api/health/report",
  risk: "/api/risk",
};
