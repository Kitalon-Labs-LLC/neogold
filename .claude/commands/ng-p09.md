---
description: "P09: Camera, Device Check, and Qualification (NeoGold work package; plan first, then wait for approval)"
---
# P09: Camera engine, device check, and camera qualification

Phase 3, Camera and vision. Depends on: P08. Backlog: NG-004, NG-007, NG-008, NG-009, NG-044. Tests: TC-DEV-01 to TC-DEV-10, TC-CAP-01, TC-CAP-15.

## Read first
- docs/4_System_Architecture.md Sections 4.5, 5.1, 5.6, ADR-012
- docs/2_PRD.md FR-03, FR-04, FR-10, FR-11
- docs/6_UI_UX_Guidelines.md Sections 5 (S05 to S08, S15, S16), 7.3, 9
- docs/1_Market_Competitor_Analysis.md Section 13 (devices and brands)

## Goal
Reliable capture on budget phones: preview with live card detection, processing controls used where available and never required, recorded metadata, and the empirical qualification test that decides whether a phone may screen.

## Build
1. `:engine:camera` with CameraX and `Camera2Interop`: turn off scene modes and effects where the phone allows, converge then lock AE and AWB where supported, short burst capture, `CaptureResult` metadata (ISO, exposure, lock states, gains, hardware level). Frames are decoded downsampled; nothing is written to disk in screening mode.
2. JPEG handling for research mode: re-encode keeping only image segments and an Exif block with orientation (doc 8 Section 6.4).
3. Device check (S05): RAM, low-RAM flag, camera hardware level, brand-specific battery guidance for TECNO, Infinix, itel, Xiaomi, Samsung, Symphony, and Walton with deep links where Android allows.
4. Qualification (S07, S08): three photos of the card, repeatability under 1.5, duplicate-patch uniformity under 2.0, accuracy under 3.0 (values from config), result `QUALIFIED` or `RESEARCH_ONLY` stored in `device_profile`; re-qualification after 30 days or an OS build change.
5. A debug-only capture screen showing detection overlay and quality reasons, excluded from release builds.

## Tests
- TC-DEV-01 to TC-DEV-10 (instrumented where possible; device and bench cases scripted with Maestro and listed for the device matrix in P22).
- TC-CAP-01, TC-CAP-15.

## Done when
- Captures work on at least D1, D2, and D4 (I will run them; give me a Maestro flow and a checklist).
- No image file appears on disk after a screening capture (scripted check).

## Stop and ask me if
- A matrix phone cannot run CameraX at all.
