---
description: "P10: v0.1 Integration and Performance (NeoGold work package; plan first, then wait for approval)"
---
# P10: v0.1 integration, capture to estimate, and performance

Phase 3, Camera and vision. Depends on: P03, P05, P06, P09. Backlog: NG-025. Tests: TC-PERF-01 to TC-PERF-04, TC-EST-04, TC-EST-07 (two ABIs).

## Read first
- docs/4_System_Architecture.md Sections 4.4, 5.6, 8
- docs/9_Test_Plan_Strategy.md Sections 8 (v0.1 exit) and 11
- docs/2_PRD.md Section 12 Phase A

## Goal
The v0.1 milestone: on a real phone, card QR scan, capture, correction, estimate, and a triage result on a debug screen, within the time and memory budgets.

## Build
1. A domain-layer pipeline use case wiring card trust, camera, vision, color science, estimation, and triage, with the screening state machine from doc 4 Section 4.4 (UI renders state only).
2. Debug screen (debug builds only) showing every intermediate value and the versions record (NFR-10).
3. Macrobenchmark module with trace sections per stage of doc 4 Section 5.6; a script that runs 30 captures and reports p50 and p95 per phone.
4. Two-ABI determinism run of the golden vectors (TC-EST-07).

## Done when
- Doc 9 Section 8 v0.1 exit criteria met and recorded in `docs/test-reports/v0.1.md`, including camera qualification results for all six matrix phones and an honest note that the model has no clinical meaning yet.
- TC-PERF-01 to 04 measured on D1, D2, D4.

## Stop and ask me if
- p95 capture to result exceeds 10 s on D1 or D2; propose cuts before optimizing blindly.
