import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { getSyncStatus, triggerSync } from "./syncService";
import { ApiError } from "../client";

function jsonResponse(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

describe("syncService", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads status from the real GET /api/sync/status endpoint", async () => {
    fetch.mockResolvedValue(jsonResponse(200, { state: "IDLE" }));
    await expect(getSyncStatus()).resolves.toEqual({ state: "IDLE" });
    expect(fetch).toHaveBeenCalledWith("http://localhost:8080/api/sync/status");
  });

  it("starts a run with POST /api/sync/trigger", async () => {
    fetch.mockResolvedValue(jsonResponse(202, { outcome: "STARTED", message: "Synchronization started." }));
    const result = await triggerSync();
    expect(result.outcome).toBe("STARTED");
    expect(fetch).toHaveBeenCalledWith("http://localhost:8080/api/sync/trigger", { method: "POST" });
  });

  it("surfaces the backend's message and body on a 409 instead of pretending success", async () => {
    fetch.mockResolvedValue(
      jsonResponse(409, { outcome: "NOT_CONFIGURED", message: "No synchronization destination is configured; nothing was sent.", status: { state: "NOT_CONFIGURED" } }),
    );
    const error = await triggerSync().catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(409);
    expect(error.message).toMatch(/No synchronization destination/);
    expect(error.body.outcome).toBe("NOT_CONFIGURED");
  });

  it("falls back to a generic message when the error body is not JSON", async () => {
    fetch.mockResolvedValue({ ok: false, status: 502, json: async () => { throw new Error("no json"); } });
    await expect(triggerSync()).rejects.toThrow("Request failed (502)");
  });
});
