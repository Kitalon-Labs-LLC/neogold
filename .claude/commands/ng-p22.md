---
description: "P22: Device Matrix and Release Candidates (NeoGold work package; plan first, then wait for approval)"
---
# P22: Device-matrix verification and the v1.0 closed release candidate

Phase 10, Release v1.0. Depends on: all earlier packages. Backlog: NG-046. Tests: everything marked E2E, R, P, M, or OPS that is not yet passing, including TC-PERF-05 to TC-PERF-07, TC-USE-01 to TC-USE-05, TC-OPS-06 (halt drill), and TC-OPS-09 (fork drill).

## Read first
- docs/9_Test_Plan_Strategy.md (all)
- docs/10_Test_Cases.md (all levels E2E, R, P, M, OPS)
- docs/12_Deployment_Runbook.md Sections 4.3, 9, 12
- docs/13_Release_Notes.md Section 6

## Goal
Evidence that v1.0 works on the phones people own, collected into a test summary report, and a release candidate on Play closed testing for the study sites.

## Build
1. Maestro flows for J1, J4, enrollment, Upload now, and export; reliability scripts (`adb` kill loops, storage fill, clock changes) and a manual power-loss checklist.
2. Run plans per device D1 to D7, with what I run by hand and what runs scripted; collect results into `docs/test-reports/v1.0.md` from the template, including pass, fail, and blocked counts by area, open defects by severity, gates S1 to S9, and the line that software testing does not show clinical safety.
3. Usability session kit for the nurse panel (TC-USE-01 to 03): tasks, teach-back script in Bangla and English, and a results sheet.
4. Fill the v1.0.0 research build release notes (doc 13 Section 6) from the signed records and versions; check the 500-character limit.
5. Run `tools/trace.py docs` and list any test case still not passing with a reason.

## Done when
- Doc 9 Section 8 exit criteria for v1.0 closed release are met or each gap is listed with an owner.
- The release candidate is on the closed testing track after both approvals.

## Stop and ask me
- Before promoting to the closed track, with the test summary report attached.
