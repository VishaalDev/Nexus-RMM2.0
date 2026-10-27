import { describe, it, expect, vi, beforeEach } from "vitest";

vi.mock("../client", () => ({ apiGet: vi.fn().mockResolvedValue({}) }));

import { apiGet } from "../client";
import { getAdvisorReport, getAlerts, getProcesses } from "./advisorService";

describe("advisorService", () => {
  beforeEach(() => vi.clearAllMocks());

  it("calls the documented endpoints", async () => {
    await getAdvisorReport();
    await getProcesses();
    await getAlerts();
    await getAlerts(10);
    expect(apiGet.mock.calls.map((c) => c[0])).toEqual([
      "/api/advisor",
      "/api/processes",
      "/api/advisor/alerts?limit=50",
      "/api/advisor/alerts?limit=10",
    ]);
  });
});
