---
description: "P21: Screening Flow (Built, Hidden in v1.0) (NeoGold work package; plan first, then wait for approval)"
---
# P21: Screening flow, results, log, and due list

Phase 10, Screening. Depends on: P10, P13. Backlog: NG-012, NG-017, NG-018, NG-019 (UI), NG-021, NG-022, NG-023, NG-024, NG-026, NG-040, NG-043, NG-047, NG-048, NG-051, NG-056. Tests: TC-UI-01 to TC-UI-03, TC-UI-09 to TC-UI-14, TC-LOG-01 to TC-LOG-03, TC-LOG-06, TC-LOG-08 to TC-LOG-10, TC-LOG-12, TC-REL-01 to TC-REL-07, TC-TRI-26 (UI side), TC-CARD-08, TC-CARD-09, TC-CAP-13, TC-CAP-14, TC-CAP-16.

## Read first
- docs/2_PRD.md J1 to J3, FR-05 to FR-24, FR-36, FR-37, D10 to D12
- docs/6_UI_UX_Guidelines.md Sections 3, 5, 6.1, 7.1 to 7.4, 8, 9, 10
- docs/4_System_Architecture.md Sections 4.4 and 6
- docs/8_Security_Compliance_Plan.md Section 14 (H2, H3, H10, H11)

## Goal
The full screening flow from Home to saved result, exactly as designed, shipped behind `triageEnabled` so v1.0 study builds never show a result.

## Build
1. S09 Home with the experimental label and the rechecks-due list (works with notifications blocked).
2. S10a guardian consent (no default; "No" ends the screen, nothing saved), S10 birth time with "Phone time now" and the clock checks (before build time, automatic time off, research offset drift), gestational age stepper with no pre-filled value, S11 to S13 safety questions with no defaults, S14 rule outcomes.
3. S15 to S19 capture, retake guidance per reason, optional forehead photo, analyzing.
4. S20 result: "Suggested next step", action with color, icon, border, and words, "You decide, using your clinical judgment.", range bar, "Always confirm with a blood test.", versions line; "What to tell the mother" card (NG-047).
5. S21 and S22 log and session detail (what was shown, reconstructable), register number field with validation, log export (register numbers excluded by default), recheck notification without baby details (NG-048).
6. `triageEnabled` false: the screening entry point is hidden in the study build and every path that would show a result shows the blood test message instead (TC-TRI-26).

## Tests
- The listed TC IDs as Compose UI tests and Maestro flows; screenshot tests for S10a, S10, S20 at 320 dp in both languages.

## Done when
- With the synthetic config and `triageEnabled: true` in a debug build, all flows pass; with `triageEnabled: false` in the release build, no result is reachable.
- safety-reviewer subagent has reviewed the diff, especially wording, defaults, and gating.
