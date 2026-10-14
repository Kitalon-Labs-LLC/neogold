---
description: "P08: Vision Engine (NeoGold work package; plan first, then wait for approval)"
---
# P08: Vision engine (markers, sampling, quality gate)

Phase 3, Camera and vision. Depends on: approved P02, P04. Backlog: NG-010, NG-011, NG-014. Tests: TC-CAP-02 to TC-CAP-12, TC-CAP-17, TC-CARD-07 (bench).

## Read first
- card/spec/card-v1.md
- docs/4_System_Architecture.md Sections 4.3 (computer vision row), 5.2, 5.3, 5.6
- docs/10_Test_Cases.md Section 4
- docs/9_Test_Plan_Strategy.md Sections 5 and 6

## Goal
From one decoded frame: find the card, warp it to the canonical image, sample every patch and the skin window, and return pass or a list of quality failure reasons with the exact codes in doc 4 Section 5.3.

## Build
1. **Spike first (one session, then stop):** build two throwaway variants of marker detection, OpenCV Android from Maven Central (`org.opencv:opencv`, objdetect ArUco) and the AprilTag C library through JNI. For each, report: download size added per ABI, detection rate and time on the synthetic set at three scales and five angles, and 16 KB alignment status. Recommend one. **Stop for my decision.**
2. After the decision, `:engine:vision`:
   - Marker detection on a downscaled frame (long edge about 1600 px), homography, warp to the canonical size from the card spec.
   - Patch and window sampling via `:engine:colorscience` trimmed means.
   - Quality checks in doc 4 Section 5.3 order, each returning its reason code: `NO_CARD`, `CARD_ANGLE`, `NONUNIFORM_PATCHES`, `BLUR`, `GLARE`, `UNDEREXPOSED`, `OVEREXPOSED`, `UNEVEN_LIGHT`, `HIGH_RESIDUAL`, `WINDOW_NOT_SKIN`. Thresholds come from the config `qualityThresholds`, never constants in code.
   - Skin plausibility (chromaticity range, uniformity, no hard edges).
3. A synthetic card renderer for tests (from `card/spec/card-v1.json`): rotation, scale, perspective, blur, glare spots, exposure, uneven light, objects in the window.
4. `testdata/bad/` and `testdata/golden/` manifests; bench images are added by me as I capture them.

## Constraints
- Memory: one frame at a time, buffers reused; report peak allocation in tests.
- Native code only for marker detection; everything else Kotlin.

## Tests
- TC-CAP-02, 05, 06, 07, 10, 11, 12, 17 as JVM or Robolectric tests on synthetic images; TC-CAP-03, 04, 08, 09 and TC-CARD-07 (home-printed copy of a genuine QR) as instrumented or bench tests with placeholders until bench images exist (mark them clearly as pending bench data, not skipped silently).

## Done when
- Synthetic suite passes; the bad set is 100% rejected with labeled reasons.
- Size added to the APK reported per ABI; `check_16kb.sh` passes.
- safety-reviewer subagent has reviewed the quality gate.

## Stop and ask me
- After the spike (step 1).
