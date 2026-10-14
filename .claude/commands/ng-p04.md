---
description: "P04: Color Science Engine (NeoGold work package; plan first, then wait for approval)"
---
# P04: Color science engine

Phase 1, Specs and engines. Depends on: P01. Backlog: NG-013. Tests: TC-EST-02, TC-EST-03, plus standard-conformance tests below.

## Read first
- docs/4_System_Architecture.md Sections 5.2 and 5.4
- docs/9_Test_Plan_Strategy.md Section 5 (bench rig)
- card/spec/card-v1.md if P02 is approved (patch IDs); otherwise use placeholder patch sets in tests

## Goal
Pure Kotlin color math the rest of the pipeline trusts: linearization from the gray ramp, correction fitting, XYZ and CIELAB, Delta E 2000, trimmed statistics.

## Build
1. `:engine:colorscience`:
   - Trimmed mean (drop brightest and darkest 10%) over a pixel region; inner-region selection (60% for patches, 70% for the window).
   - Per-channel monotonic linearization fitted from observed gray patches to measured gray reflectances (monotone piecewise-linear or a monotone spline; justify the choice in a short ADR).
   - 3 x 3 least-squares correction from linearized RGB to XYZ (D65), and a second-order root-polynomial correction behind a flag for later evaluation.
   - XYZ to CIELAB (D65), CIELAB to LCh, and CIEDE2000.
   - Correction residual (mean Delta E 2000 on patches) for the quality gate.
2. All powers, roots, and exponentials through `StrictMath`.
3. A small matrix and least-squares implementation (no external linear-algebra library) with tests for ill-conditioned inputs.

## Tests
- CIEDE2000 against the published Sharma, Wu, and Dalal (2005) test pairs: all within 1e-4.
- Round trips: sRGB to XYZ to Lab and back within 1e-9 on a grid.
- Correction recovers a known synthetic distortion (random 3 x 3 plus tone curve) to under 0.5 Delta E on held-out patches.
- TC-EST-03: a colored ring at the window edge does not change the skin value.
- Determinism: identical results across repeated runs (prepares TC-EST-07).

## Done when
- `./gradlew :engine:colorscience:test` passes; coverage report attached.
- Golden vectors for P06 and P07 written to `testdata/golden/colorscience/` with a SHA-256 manifest.

## Stop and ask me if
- The gray-ramp fit is unstable with fewer than 6 gray patches; that changes the card spec.
