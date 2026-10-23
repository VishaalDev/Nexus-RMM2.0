/**
 * Module 3 (offline synchronization) endpoints, kept separate so Module 1's
 * endpoints.js and Module 2's healthEndpoints.js are untouched.
 */
export const SYNC_ENDPOINTS = {
  status: "/api/sync/status",
  trigger: "/api/sync/trigger",
};
