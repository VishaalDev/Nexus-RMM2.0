/** Shaped like the real GET /api/advisor, /api/advisor/alerts and /api/processes responses. */
export const ADVISOR_REPORT = {
  generatedAt: "2026-10-24T10:00:00Z",
  headline: "1 critical issue and 1 warning need attention.",
  healthScore: 58,
  healthStatus: "DEGRADED",
  healthCoverage: 0.77,
  riskLevel: "HIGH",
  criticalCount: 1,
  warningCount: 1,
  infoCount: 1,
  processInsight: true,
  issues: [
    {
      key: "cpu:load",
      category: "CPU",
      severity: "CRITICAL",
      title: "CPU is overloaded",
      summary: "Average CPU is 96.0% (critical threshold 90.0%). Right now chrome.exe (40 processes) is using 62.0% of total CPU.",
      sourceCodes: ["CPU_SUSTAINED_CRITICAL"],
      evidence: { average: 96, topProcess: "chrome.exe", dominant: true, attribution: "should be hidden-from-table", attributionNote: "hidden" },
      recommendations: [
        { code: "process.chrome", action: "Close tabs and windows you no longer need.", why: "Biggest contributor.", effort: "EASY", expectedBenefit: "Targets the main contributor." },
        { code: "cpu.identify", action: "Open Task Manager and sort by CPU.", why: "Seeing which program is fastest.", effort: "EASY", expectedBenefit: "Frees CPU." },
        { code: "cpu.startup", action: "Disable startup apps you do not need.", why: "They run all day.", effort: "MODERATE", expectedBenefit: "Lower baseline." },
      ],
      confidence: "HIGH",
      priority: 320,
      firstSeen: "2026-10-24T09:30:00Z",
      lastSeen: "2026-10-24T10:00:00Z",
    },
    {
      key: "thermal:heat",
      category: "THERMAL",
      severity: "WARNING",
      title: "CPU is running hot",
      summary: "CPU temperature 95th percentile is 85.0.",
      sourceCodes: ["THERMAL_HIGH"],
      evidence: {},
      recommendations: [
        { code: "thermal.cooling", action: "Clean dust from fans.", why: "Load was low.", effort: "ADVANCED", expectedBenefit: "Restores cooling.", caution: "Power off and unplug before opening a case." },
      ],
      confidence: "MEDIUM",
      priority: 210,
    },
    {
      key: "visibility:gaps",
      category: "VISIBILITY",
      severity: "INFO",
      title: "Some areas cannot be assessed",
      summary: "These areas have no usable data (this is not the same as healthy): Thermal.",
      sourceCodes: [],
      evidence: { THERMAL: "No temperature samples." },
      recommendations: [],
      confidence: "HIGH",
      priority: 100,
    },
  ],
  topActions: [
    {
      issueKey: "cpu:load",
      issueTitle: "CPU is overloaded",
      severity: "CRITICAL",
      recommendation: { code: "process.chrome", action: "Close tabs and windows you no longer need.", why: "x", effort: "EASY", expectedBenefit: "y" },
    },
  ],
  limitations: ["It never changes anything on your computer itself.", "The alert history is kept in memory."],
};

export const QUIET_REPORT = {
  ...ADVISOR_REPORT,
  headline: "No problems found.",
  criticalCount: 0,
  warningCount: 0,
  infoCount: 0,
  issues: [],
  topActions: [],
};

export const ALERTS = [
  { time: "2026-10-24T09:30:00Z", type: "NEW", issueKey: "cpu:load", category: "CPU", severity: "CRITICAL", title: "CPU is overloaded" },
];

export const PROCESSES = {
  capturedAt: "2026-10-24T10:00:00Z",
  available: true,
  cpuAvailable: true,
  processCount: 312,
  threadCount: 4100,
  uptimeSeconds: 90000,
  totalMemoryBytes: 17179869184,
  totalCpuPercent: 71.5,
  topByCpu: [
    { name: "chrome.exe", processCount: 40, cpuPercent: 62, memoryBytes: 6442450944, memoryPercent: 37.5, threadCount: 400, description: "Google Chrome (all tabs).", advice: "Close tabs.", systemComponent: false },
  ],
  topByMemory: [
    { name: "chrome.exe", processCount: 40, cpuPercent: 62, memoryBytes: 6442450944, memoryPercent: 37.5, threadCount: 400, description: "Google Chrome (all tabs).", advice: "Close tabs.", systemComponent: false },
    { name: "mystery.exe", processCount: 1, cpuPercent: 1, memoryBytes: 104857600, memoryPercent: 0.6, threadCount: 4, systemComponent: false },
  ],
  note: "Memory is the sum of each process's resident set.",
};
