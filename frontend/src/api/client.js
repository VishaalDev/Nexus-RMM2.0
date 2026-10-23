const BASE_URL = "http://localhost:8080";

/**
 * Minimal fetch wrapper used by every endpoint module in src/api/services.
 * Centralizing this means switching base URL, adding auth headers later,
 * or changing error handling only ever needs to happen in one place.
 */
export async function apiGet(path) {
  const response = await fetch(`${BASE_URL}${path}`);

  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body = await response.json();
      if (body?.message) message = body.message;
    } catch {
      // response wasn't JSON; keep the generic message
    }
    throw new ApiError(message, response.status);
  }

  return response.json();
}

/**
 * POST with no request body. Used by actions (e.g. Sync Now). A non-2xx
 * answer throws an ApiError that also carries the parsed JSON body, because
 * some endpoints (POST /api/sync/trigger answers 409) return useful state
 * alongside the error.
 */
export async function apiPost(path) {
  const response = await fetch(`${BASE_URL}${path}`, { method: "POST" });

  let body = null;
  try {
    body = await response.json();
  } catch {
    // empty or non-JSON body
  }

  if (!response.ok) {
    throw new ApiError(body?.message ?? `Request failed (${response.status})`, response.status, body);
  }
  return body;
}

export class ApiError extends Error {
  constructor(message, status, body = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.body = body;
  }
}
