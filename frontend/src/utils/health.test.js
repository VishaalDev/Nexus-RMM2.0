import { describe, it, expect } from "vitest";
import {
  confidenceLabel,
  countScored,
  formatHorizonDays,
  formatPercent,
  formatScore,
  sortFindings,
} from "./health";

describe("health utils", () => {
  it("never renders a missing score as 0 or blank", () => {
    expect(formatScore(null)).toBe("\u2014");
    expect(formatScore(undefined)).toBe("\u2014");
    expect(formatScore(0)).toBe("0");
    expect(formatScore(87)).toBe("87");
  });

  it("formats coverage fractions and tolerates missing values", () => {
    expect(formatPercent(0.657)).toBe("66%");
    expect(formatPercent(1)).toBe("100%");
    expect(formatPercent(null)).toBe("\u2014");
    expect(formatPercent(Number.NaN)).toBe("\u2014");
  });

  it("formats a projected horizon, or nothing when there is none", () => {
    expect(formatHorizonDays(5.04)).toBe("about 5.0 days");
    expect(formatHorizonDays(0.4)).toBe("less than 1 day");
    expect(formatHorizonDays(null)).toBeNull();
  });

  it("sorts findings most severe first without mutating the input", () => {
    const input = [
      { severity: "INFO", code: "a" },
      { severity: "CRITICAL", code: "b" },
      { severity: "WARNING", code: "c" },
    ];
    expect(sortFindings(input).map((f) => f.code)).toEqual(["b", "c", "a"]);
    expect(input[0].code).toBe("a");
    expect(sortFindings(undefined)).toEqual([]);
  });

  it("counts components that produced a score", () => {
    expect(countScored([{ score: 90 }, { score: null }, { score: 0 }])).toEqual({ scored: 2, total: 3 });
    expect(countScored(undefined)).toEqual({ scored: 0, total: 0 });
  });

  it("capitalizes confidence and handles absence", () => {
    expect(confidenceLabel("MEDIUM")).toBe("Medium");
    expect(confidenceLabel(undefined)).toBe("Unknown");
  });
});

import { averageTelemetryCoverage, formatEvidence, riskPresentation, severityPoints } from "./health";

describe("risk presentation", () => {
  it("maps NOT_ASSESSED to its own state", () => {
    expect(riskPresentation("NOT_ASSESSED", "LOW").kind).toBe("not-assessed");
  });

  it("treats Low risk with anything but Medium/High confidence as limited evidence", () => {
    for (const confidence of ["LOW", undefined, null, "UNKNOWN"]) {
      const p = riskPresentation("LOW", confidence);
      expect(p.kind).toBe("limited-evidence");
      expect(p.label).not.toBe("Low risk");
      expect(p.color).toBe("var(--color-text-muted)");
    }
  });

  it("keeps a genuine Low risk (Medium/High confidence) as assessed and green", () => {
    for (const confidence of ["MEDIUM", "HIGH"]) {
      const p = riskPresentation("LOW", confidence);
      expect(p).toMatchObject({ kind: "assessed", label: "Low risk", color: "var(--color-success)" });
    }
  });

  it("never downgrades an actual indicator because confidence is low", () => {
    expect(riskPresentation("HIGH", "LOW")).toMatchObject({ kind: "assessed", label: "High risk" });
    expect(riskPresentation("CRITICAL", "LOW")).toMatchObject({ kind: "assessed", label: "Critical risk" });
  });
});

describe("factor evidence and severity", () => {
  it("scales severity 0..1 to 0..100", () => {
    expect(severityPoints(0.57)).toBe(57);
    expect(severityPoints(1)).toBe(100);
    expect(severityPoints(undefined)).toBe(0);
  });

  it("explains fractionPercent according to the factor it belongs to", () => {
    expect(formatEvidence("SUSTAINED_HEAT", { fractionPercent: 76.7 })).toEqual([
      { key: "fractionPercent", label: "Samples above the warning temperature", value: "76.7%" },
    ]);
    expect(formatEvidence("CRITICAL_TEMPERATURE", { fractionPercent: 22.4 })[0].label).toBe(
      "Samples at or above the critical temperature",
    );
  });

  it("rounds numbers, keeps strings verbatim, and never hides unknown keys", () => {
    const out = formatEvidence("STORAGE_FILLING", { instance: "C:\\", daysToFull: 4.999, somethingNew: 3 });
    expect(out.find((e) => e.key === "instance").value).toBe("C:\\");
    expect(out.find((e) => e.key === "daysToFull").value).toBe("5 days");
    expect(out.find((e) => e.key === "somethingNew")).toEqual({ key: "somethingNew", label: "somethingNew", value: "3" });
    expect(formatEvidence("X", undefined)).toEqual([]);
  });
});

describe("average telemetry coverage", () => {
  it("averages only the components that were scored", () => {
    expect(
      averageTelemetryCoverage([
        { score: 90, dataCoverage: 0.2 },
        { score: 80, dataCoverage: 0.4 },
        { score: null, dataCoverage: 0 },
      ]),
    ).toBeCloseTo(0.3);
  });

  it("is null, not zero, when nothing was scored", () => {
    expect(averageTelemetryCoverage([{ score: null, dataCoverage: 0 }])).toBeNull();
    expect(averageTelemetryCoverage(undefined)).toBeNull();
  });
});
