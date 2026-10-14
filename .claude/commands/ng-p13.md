---
description: "P13: Design System, Onboarding, Settings, Bangla (NeoGold work package; plan first, then wait for approval)"
---
# P13: Design system, onboarding, settings, and Bangla

Phase 5, App shell. Depends on: P09, P11. Backlog: NG-002, NG-003, NG-005, NG-027, NG-038 (localization guide), NG-046, NG-055. Tests: TC-ONB-01 to TC-ONB-06, TC-UI-04 to TC-UI-08, TC-A11Y-01 to TC-A11Y-05, TC-DEV-10.

## Read first
- docs/6_UI_UX_Guidelines.md Sections 1 to 5, 8, 10, 11, 12, 13, 14
- docs/2_PRD.md Personas, FR-01, FR-02, FR-32, NFR-06, NFR-07
- docs/4_System_Architecture.md Section 4.6

## Goal
The visual and language foundation every screen uses, plus the first-run flow (S01 to S08) and settings (S23 to S25).

## Build
1. `:core:designsystem`: tokens from doc 6 Section 2 (colors with the verified contrast pairs, Noto Sans and Noto Sans Bengali, spacing, shapes, 48 dp minimum targets), the `Ng*` components in doc 6 Section 4, dark theme per Section 13.
2. Strings: every string from doc 6 Section 10.4 in `values/` and `values-bn/`; Western digits for clinical numbers; a Gradle string lint that fails on hardcoded strings, missing keys in either language, and any banned word in doc 6 Section 10.2 (English and Bangla).
3. Screens S01 to S08 wired to P09's device check and qualification; S23 settings (language, retention 30 to 365, keep screening log, app lock, crash report opt-in); S24 About and model card; S25 delete all (calls P11).
4. In-app update prompt (NG-055) with Play In-App Updates: flexible by default, immediate for priority 4 or 5; tested with `FakeAppUpdateManager`.
5. Supported device list (NG-046) shown on S05.
6. `docs/LOCALIZATION.md`: how a fork adds a language.

## Tests
- The listed TC IDs; screenshot tests at 320 dp and 360 dp, font scales 1.0, 1.3, 2.0, both languages, light and dark.

## Done when
- String lint passes; contrast test passes for every token pair.
- Bangla strings marked "draft for nurse panel" in a reviewer file the panel can sign.
