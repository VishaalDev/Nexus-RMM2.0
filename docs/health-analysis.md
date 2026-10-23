# Module 2 — Health Analysis & Failure Risk

Turns the telemetry Module 1 stores into (a) a per-component **health score**
describing the present, and (b) an explainable **failure-risk index**
describing conditions that tend to precede problems. Both are computed on
demand from `metric_sample` and `hardware_event`; Module 2 writes nothing.

## Principles (why the numbers look the way they do)

1. **No data is never "healthy".** A component that cannot be assessed returns
   `status: NO_DATA` with a plain-language `noDataReason` and **no score key**.
   It is excluded from the overall score and lowers the report's
   `dataCoverage`. Same for risk: `NOT_ASSESSED`, never `LOW`.
2. **Every number carries its provenance.** Each component has a `basis` line
   saying what the score is computed from — and what it is *not*.
3. **Heuristics, not a model.** There are no failure labels to train or
   calibrate against. Scores come from explicit curves and rules documented
   below. The risk index is a *severity index*, not a probability.
4. **No claim without evidence.** Trends need enough buckets, span, density and
   fit; anomalies need a baseline with spread. Otherwise nothing is asserted.
5. **Health is now; risk looks ahead.** A volume that is 75% full but filling
   5 points/day is *Healthy* (it has room today) and *Critical risk* (it fills
   in ~5 days). That is intended.

## Architecture

```
HealthAnalysisController / RiskController      (thin, Spring)        com.nexusrmm.health.web
        └─ HealthFacade  (snapshot + TTL cache, plain Java)          …health.service
              ├─ HealthAnalysisEngine ── 8 × ComponentAnalyzer       …health.analysis
              ├─ RiskEngine                                          …health.risk
              └─ HealthDataProvider  (interface)                     …health.provider
                    └─ RepositoryHealthDataProvider (Spring/JPA)     …health.service
```

The engines depend only on the `HealthDataProvider` interface and the
immutable `HealthSettings` record — never on entities, repositories or Spring —
so they are unit-tested with an in-memory provider. Module 1's classes are not
modified beyond the six files listed in `INSTALL.md`.

## The eight analyzers

Windows: **short** = last 60 min (current condition), **long** = 7 days
(trend / baseline, 30-min buckets). A series needs `min-samples` (12) valid
samples in the short window or it is `NO_DATA`.

| Component | Scored from | Penalty (score = 100 − penalty) |
|---|---|---|
| **CPU** | average and saturation share, short window | curve(avg): (warn−20→0), (warn→25), (crit→50), (100→60) **+** ramp(share of samples ≥ crit: 5%→0, 60%→40). Rising trend: INFO only |
| **Memory** | same as CPU with memory thresholds | same shape |
| **Storage capacity** | latest utilization per volume | curve: (warn−15→0), (warn→25), (crit→60), (100→100). Component = ½·worst + ½·mean volume. Days-to-full is a finding + risk input, not part of the score |
| **Storage I/O** | read/write throughput vs the machine's own 7-day baseline | surge: ramp(robust z: 3.5→0, 10.5→15) per direction (max 30). Quiet is never penalized. **No baseline ⇒ NO_DATA** |
| **Thermal** | CPU temperature, 95th percentile | curve: (warn−15→0), (warn→25), (crit→60), (105→100). Sensor unavailable ⇒ NO_DATA with the provider's diagnostic |
| **Battery** | usage pattern only | ramp(share of long-window buckets ≤ low%: 5%→0, 50%→40) + 8/15 if low/critical **and discharging**. No battery ⇒ NO_DATA "Not applicable" |
| **Network** | throughput vs own baseline + adapter connectivity | surge ramp max 10 per direction; quiet is INFO. All adapters down with explicit UNAVAILABLE rows ⇒ 30 penalty. No rows at all ⇒ NO_DATA (collector off ≠ outage) |
| **Reliability** | Windows events, 7 days | per event: base × level (Critical ×1.5) × recency (≤24 h 1.0, ≤72 h 0.7, older 0.4); capped per category; total ≤ 100. Monitor not applicable / poll failed ⇒ NO_DATA |

Reliability bases and caps: HARDWARE (WHEA) 15/60 · STORAGE 10/60 · POWER 8/40 ·
DRIVER 3/15 · other 3/15.

### Overall score

Weighted mean of components that have a score, **capped at (lowest component
score + 30)** so one failing component cannot be averaged away.
`dataCoverage` = share of total weight that had data.

Status cut-offs: **Healthy ≥ 85 · Fair ≥ 65 · Degraded ≥ 40 · Critical < 40**.

## Risk index

Each assessed component has rule-based factors with a severity 0–1. Factors
combine as `1 − ∏(1 − severity)`; components combine the same way after
multiplying by their weight (reliability 1.0, storage 0.9, thermal 0.8,
memory 0.5, CPU 0.4, battery 0.4). Levels: **Low ≤ 20 · Moderate ≤ 45 · High ≤ 70 · Critical > 70**.

| Component | Factors (severity) |
|---|---|
| Storage | NEAR_FULL: (warn→0.30, crit→0.70, 100→1.0) · FILLING: days-to-full (≤7 d→0.80, 30 d→0.50, 90 d→0.15) |
| Thermal | SUSTAINED_HEAT: ramp(share ≥ warn: 10%→0, 80%→0.60) · CRITICAL_TEMPERATURE: 0.5 + min(0.5, 2×share ≥ crit) · RISING_TEMPERATURE 0.30 |
| CPU | SUSTAINED_SATURATION: ramp(share ≥ crit: 25%→0, 100%→0.40) |
| Memory | SUSTAINED_MEMORY_PRESSURE 0.50 · RISING_MEMORY_USE 0.40 |
| Battery | CHRONIC_DEEP_DISCHARGE: ramp(dwell: 20%→0, 80%→0.50) |
| Reliability | HARDWARE_ERRORS min(0.9, 0.40+0.10n) · STORAGE_ERRORS min(0.85, 0.35+0.05n) · POWER_INSTABILITY min(0.65, 0.25+0.10n) · EVENT_ESCALATION 0.30 |

`horizonDays` is the shortest projected time-to-event (storage fill).
Storage I/O and Network are `NOT_ASSESSED` for risk by design.

**Evidence confidence** (not the risk itself): HIGH needs ≥ 7 days of history
and ≥ 60% data coverage; MEDIUM ≥ 24 h and ≥ 30%; otherwise LOW. History
that is *sparse* — under 25% of the bucket slots in its span populated, e.g. a
few short sessions spread over days — is always LOW.

### Trend eligibility
A linear trend is reported only if **all** hold: ≥ 24 populated buckets, ≥ 24 h
span, density ≥ 0.25, R² ≥ 0.5. Otherwise no trend is claimed.

## Configuration
Everything above is under `nexus.health.*` in `application.yml` (windows,
thresholds, trend/anomaly bars, scoring cut-offs, weights). Invalid
combinations (e.g. `cpu-warn ≥ cpu-critical`, misordered cut-offs, long-window
≤ short-window) **fail startup** with the offending values. Overriding one
weight keeps the others at their defaults.

## Limitations (also returned in every report)
- No GPU health (Module 1 reports GPU metrics `UNSUPPORTED`).
- No SMART/NVMe drive health, no disk latency/queue depth.
- No battery wear/capacity/cycle count — battery scoring is usage-pattern only.
- No packet loss/errors/latency — network is activity + connectivity only.
- Event history only covers the time the app has been polling.
- Thresholds are conventional defaults, not calibrated to this fleet.
- Cost: a request reads up to 7 days of bucketed history per series (native
  SQL `GROUP BY`); results are cached `cache-ttl-seconds` (10 s). Module 1 has
  no retention, so very large databases will make this slower over time.

## Testing
See `INSTALL.md` for what was run where, and the honest list of what was not.
