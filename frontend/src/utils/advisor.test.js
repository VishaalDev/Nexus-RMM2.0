import { describe, it, expect } from "vitest";
import { attentionCount, categoryLabel, evidenceEntries, formatAge, formatUptime, openFor } from "./advisor";

const NOW = Date.parse("2026-10-24T12:00:00Z");

describe("formatAge", () => {
  it("formats minutes, hours and days", () => {
    expect(formatAge("2026-10-24T11:55:00Z", NOW)).toBe("5 min");
    expect(formatAge("2026-10-24T09:50:00Z", NOW)).toBe("2 h 10 min");
    expect(formatAge("2026-10-24T10:00:00Z", NOW)).toBe("2 h");
    expect(formatAge("2026-10-21T08:00:00Z", NOW)).toBe("3 d 4 h");
  });
  it("is null for unknown, invalid or future times instead of inventing an age", () => {
    expect(formatAge(null, NOW)).toBeNull();
    expect(formatAge("garbage", NOW)).toBeNull();
    expect(formatAge("2026-10-24T13:00:00Z", NOW)).toBeNull();
  });
  it("describes a just-detected issue", () => {
    expect(openFor("2026-10-24T11:59:40Z", NOW)).toBe("Just detected");
    expect(openFor("2026-10-24T11:50:00Z", NOW)).toBe("Open for 10 min");
    expect(openFor(undefined, NOW)).toBeNull();
  });
});

describe("formatUptime", () => {
  it("formats and handles missing values", () => {
    expect(formatUptime(90000)).toBe("1 d 1 h");
    expect(formatUptime(3700)).toBe("1 h 1 min");
    expect(formatUptime(120)).toBe("2 min");
    expect(formatUptime(null)).toBe("N/A");
  });
});

describe("evidenceEntries", () => {
  it("keeps flat values, rounds numbers, and drops housekeeping and nested keys", () => {
    const rows = evidenceEntries({ average: 96.04, daysToFull: 5, topProcess: "chrome.exe", dominant: true, attribution: "x", attributionNote: "y", nested: { a: 1 }, nothing: null });
    expect(rows.map((r) => r.key)).toEqual(["average", "daysToFull", "topProcess"]);
    expect(rows[0]).toMatchObject({ label: "Average", value: "96" });
    expect(rows[1].label).toBe("Days to full");
  });
  it("handles missing evidence", () => {
    expect(evidenceEntries(undefined)).toEqual([]);
  });
});

describe("misc", () => {
  it("counts only warnings and criticals as needing attention", () => {
    expect(attentionCount({ criticalCount: 1, warningCount: 2, infoCount: 5 })).toBe(3);
    expect(attentionCount(null)).toBe(0);
  });
  it("labels categories and falls back safely", () => {
    expect(categoryLabel("STORAGE_CAPACITY")).toBe("Storage capacity");
    expect(categoryLabel("SOMETHING_NEW")).toBe("SOMETHING_NEW");
  });
});
