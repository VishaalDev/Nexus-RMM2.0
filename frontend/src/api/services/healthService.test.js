import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { getHealthReport, getRiskReport } from "./healthService";

describe("healthService", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("requests the Module 2 report and risk endpoints (not the liveness endpoint)", async () => {
    fetch.mockResolvedValue({ ok: true, json: async () => ({}) });
    await getHealthReport();
    await getRiskReport();
    expect(fetch.mock.calls[0][0]).toBe("http://localhost:8080/api/health/report");
    expect(fetch.mock.calls[1][0]).toBe("http://localhost:8080/api/risk");
  });

  it("surfaces the backend's error message", async () => {
    fetch.mockResolvedValue({ ok: false, status: 400, json: async () => ({ message: "Unknown component" }) });
    await expect(getHealthReport()).rejects.toThrow("Unknown component");
  });
});
