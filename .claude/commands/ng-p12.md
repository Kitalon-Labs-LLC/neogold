---
description: "P12: Integrity, Privacy Controls, App Lock, Crash Handling (NeoGold work package; plan first, then wait for approval)"
---
# P12: Integrity, privacy controls, app lock, and crash handling

Phase 4, Data and security. Depends on: P11. Backlog: NG-049, NG-054, NG-057. Tests: TC-PRIV-01 to TC-PRIV-05, TC-SEC-03, TC-SEC-09.

## Read first
- docs/4_System_Architecture.md Section 12
- docs/8_Security_Compliance_Plan.md Sections 6.1, 6.3, 6.5, 6.6
- docs/6_UI_UX_Guidelines.md Section 11

## Goal
The app refuses to run modified clinical assets, protects screens on shared phones, locks if the facility wants it, and records crashes without personal data.

## Build
1. Bundle manifest: build-time task that hashes model, triage config, card trust list, and study trust list and signs the manifest with K3 (from a Gradle property in CI; a test key locally); startup verification that disables screening on any mismatch.
2. `FLAG_SECURE` helper applied to every screen showing a register number or research data; `setRecentsScreenshotEnabled(false)` on API 33 and up; `filterTouchesWhenObscured` on confirmation and consent buttons for API 26 to 30.
3. Optional app lock with `BiometricPrompt` allowing device credentials, with the `KeyguardManager` fallback on API 28 and 29; re-lock after 5 minutes in the background.
4. In-house crash handler: class names and stack frames only, exception messages dropped, encrypted local file, "Share crash report" through the share sheet on next launch; nothing sent automatically.
5. Release logging: R8 rules that strip `android.util.Log`; a Timber release tree that drops below `WARN`.
6. Update `tools/check_manifest.py` allow-lists only if a permission or component is added, and doc 8 Section 6.1 in the same PR.

## Tests
- The listed TC IDs; TC-PRIV-03 as an instrumented backup test where possible.

## Done when
- TC-SEC-09 shown in the PR: a re-signed APK with a changed config asset disables screening.
- privacy-security-reviewer subagent has reviewed the diff.
