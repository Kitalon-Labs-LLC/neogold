---
description: "P29: Journey Runner and Error-Free Rate (NeoGold work package; plan first, then wait for approval)"
---
# P29: Journey runner and error-free session rate

Phase 11, Agent quality loop. Depends on: P25, P28 (shared drive and camera setup). Backlog: none (process). Tests: reuses TC-PERF-01, TC-PERF-05, TC-TRI-26 (UI side), and the E2E cases in doc 10 Sections 15 and 16; tooling tests in tools/tests/.

## Read first
- docs/2_PRD.md Section 6 (J1 to J4) and NFR-02
- docs/9_Test_Plan_Strategy.md Sections 3, 4.2, 7, 8, and 11
- docs/10_Test_Cases.md Sections 15 and 16, and the Maestro flows from P09, P21, and P22
- docs/12_Deployment_Runbook.md Sections 4.1 and 9

## Goal
A repeatable answer to "how often does a health worker get all the way through without anything going wrong?", per journey and per phone, with a release gate on it. NeoGold has no analytics (FR-34), so this number comes from the lab, not the field.

## Build
1. `journeys/catalog.yaml`. Each journey has an ID, its PRD source, a priority (P1 or P2), preconditions (build, config, seeded data, network state), its Maestro flow, and named steps with time budgets. Include at least these journeys:
   - J1: first launch and device check
   - J2: screening with consent, inputs, capture, and result (debug build, synthetic config, `triageEnabled: true`)
   - J2-hidden: the study build, where no result is reachable (TC-TRI-26)
   - J3: offline home visit
   - J4: research enrollment, capture, and TSB entry
   - Upload now
   - Encrypted export
   - App lock and unlock
   - Delete all data
   - Leave study

   Reuse the existing flows and add the missing ones.
2. `tools/journeys/run.py --device <serial> --runs 20 --priority P1`:
   - Runs each flow N times, from a clean app state each time, and records timing per step.
   - Counts these as app failures:
     - a crash or ANR (from `adb logcat -b crash` and ANR traces)
     - a wrong or missing screen
     - an error event in the app's local log (debug build)
     - a step over its time budget
   - Keeps harness failures (a selector that flaked) separate:
     - A harness failure is rerun once and flagged.
     - If more than 5% of runs have harness failures, the report is marked invalid.
     - Harness problems get fixed in the flow and are never counted as passes.
3. Output: `reports/journeys/<version>/<device>-<date>.json`, matching `tools/journeys/schema.json`, plus a short Markdown summary. The JSON holds:
   - the build: version, commit, config hash, `triageEnabled`
   - the device: matrix ID D1 to D7, or the emulator image
   - per journey: runs, error-free runs, the rate, failures with evidence, and p50 and p95 per step against its budget
4. `tools/check_journeys.py`, the release gate. For the release commit, it requires:
   - valid reports for D1 to D4
   - every P1 journey error-free in 20 of 20 runs
   - on D1 and D2, J2 under 60 s at p95 and capture to result under 10 s at p95 (NFR-02)

   In this PR, propose adding the gate to doc 9 Section 8 and to the release-candidate steps in doc 12 Section 4.1.
5. Nightly CI: run the journeys that don't need the camera on an API 34 emulator, to track the trend. A failure opens a defect automatically, as doc 9 Section 7 says for nightly runs.
6. `.claude/skills/ng-journeys/SKILL.md` (`disable-model-invocation: true`):
   - Runs on a device, writes the report, and compares it with the previous run.
   - For each app failure, opens a defect from the doc 11 template with the evidence.
   - May propose a fix, but doesn't write one in the same session.
7. Register the package in docs/14 and the CLAUDE.md Commands table.

## Constraints
- Synthetic and bench data only. Reset the app between runs.
- Never mark a flaky step as passing, and never raise a time budget to make it pass.

## Done when
- One full P1 run is done on the emulator and on D1, with the reports committed.
- `check_journeys.py` passes, or fails with clear reasons.

## Stop and ask me
- Before changing doc 9 Section 8 or doc 12 Section 4.1, because those are release process changes.
- If you want a field error-free number: that needs counting inside the app, which is a protocol and consent decision, not a code change.

