---
name: ng-journeys
description: Runs automated end-to-end user journeys repeatedly against an Android device or emulator, records timing and error-free session rates in reports/journeys/<version>/, diffs against prior runs, and drafts defects for app failures.
disable-model-invocation: true
argument-hint: "[--device <D1..D7|emulator>] [--runs <N>] [--priority <P1|P2|all>] [--journey <id>]"
---

# NeoGold Journey Runner & Error-Free Session Benchmark (`/ng-journeys`, P29)

Runs the journeys defined in `journeys/catalog.yaml` repeatedly from a clean app state to establish the empirical lab error-free session rate per journey and per device matrix class.

## Hard Constraints
1. **Lab Measurement Only (FR-34):** NeoGold has no field telemetry or crash tracking by design. Error-free session rates come strictly from lab benchmarking on physical matrix devices (D1 to D7) and emulators.
2. **Never Count Flakes as Passes:** Harness failures (flaky selectors) are rerun once and flagged. If harness failures exceed 5% of runs, the report is marked invalid. Flakes are never counted as passes.
3. **Never Raise Budgets:** Never increase step or journey time budgets to pass a slow device. If p95 exceeds budget, investigate performance bottlenecks or record a defect.
4. **Synthetic & Bench Data Only:** Always use bench images (`testdata/bench/`) and synthetic config (`testdata/triage/`). Never use patient data.
5. **Defect Template Compliance:** For each app failure (crash, ANR, wrong screen, app log error, time budget exceeded), draft a defect using the `docs/11_Bug_Defect_Log.md` template.
6. **Proposals Only:** You may propose a performance or reliability fix in the summary, but do not modify product or engine code in the same session.

---

## Procedure

### Step 1: Run Journey Benchmark
Run `tools/journeys/run.py` on the requested device or emulator:
```bash
# Run all P1 journeys 20 times on device D1 (Transsion reference class)
python3 tools/journeys/run.py --device D1 --runs 20 --priority P1

# Run on Android Emulator API 34
python3 tools/journeys/run.py --device emulator --runs 20 --priority P1

# Run a specific journey
python3 tools/journeys/run.py --device D1 --runs 20 --journey J2
```

Outputs written:
- `reports/journeys/<version>/<device>-<date>.json` (conforming to `tools/journeys/schema.json`)
- `reports/journeys/<version>/<device>-<date>.md`

### Step 2: Compare With Previous Run
Inspect previous reports in `reports/journeys/<version>/` for the same device ID:
- Compare error-free rate trend (e.g. 100% -> 95%).
- Compare step timings at p50 and p95 (detecting latency regressions on NFR-02 capture-to-result).
- Identify any newly failing steps or newly emerging harness flakiness.

### Step 3: Evaluate Release Gate
Execute the release gate check:
```bash
python3 tools/check_journeys.py
```
For intermediate device testing, run:
```bash
python3 tools/check_journeys.py --device <device> --min-runs 20
```

### Step 4: Open Defects for App Failures
If any app failures occurred during the run, format a defect entry following `docs/11_Bug_Defect_Log.md` Section 2:

```markdown
#### BUG-2026-NNN: <Short title describing journey failure>

| Field | Value |
|---|---|
| ID | `BUG-2026-NNN` |
| Title | <Journey ID and description of failure> |
| Found | YYYY-MM-DD |
| Source | Test |
| Build | <Version from report> |
| Device | <Device ID: D1..D7 or Emulator> |
| Area | PERF / REL / UI / CAP |
| Severity | S1 (if clinical/triage impacted) or S2 |
| Clinical | Yes (if triage/referral affected) / No |
| Hazard | H1..H11 or - |
| Priority | P1 |
| Status | New |
| Owner | Sowad Al-Mughni |
| Test case | TC-PERF-01 / TC-PERF-05 / TC-REL-01 |

**Steps to reproduce:**
1. Execute journey `<ID>` on `<device>` using `tools/journeys/run.py --device <device> --journey <ID>`
2. Step `<step_name>` failed on run `<run_index>`

**Expected:**
Step completed within budget `<budget>` s with no app errors or crashes.

**Actual:**
<Failure message and evidence from report>
```

### Step 5: Report Summary
Present a clear summary to the owner:
- Device tested and app version.
- Overall error-free session rate across all journeys.
- NFR-02 status (J2 p95 and capture-to-result p95 on D1/D2).
- Gate status: Pass or Fail with clear reasons.
- Any drafted defect entries and recommended investigations (proposals only).
