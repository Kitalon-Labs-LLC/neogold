# Spike: Marker Detection on Preview Frame Timing Budget

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | NeoGold Agent Quality Team |
| Work package / Context | P23 (Agent quality loop, Spike and assumption check) |
| Verdict | **Not confirmed on a phone** (downgraded 27 October 2026; see Section 8) |

---

## 1. Claim and Source
- **Claim:** Marker detection on preview frames runs in under 80 ms per frame when executed at 5 fps.
- **Source:** `docs/4_System_Architecture.md` line 295:
  `| Marker detection on preview frame | under 80 ms per frame, run at 5 fps [Guessing] |`
- **Confidence Tag:** `[Guessing]`

## 2. Question
- **Yes/No Question:** Can the computer vision marker detection on 640x480 preview frames complete in under 80 ms per frame across varying scales (0.75x to 1.25x) and tilt angles (0° to 45°)?
- **Measurable Pass Condition:**
  - Median and mean execution time for detecting 4 corner markers is strictly under 80.0 ms per frame.

## 3. Method and Exact Commands
- **Environment & Target:** Host Python 3.14 with OpenCV `cv2.aruco` running benchmarks across 15 geometric variations (3 scales: 0.75x, 1.00x, 1.25x; 5 tilt angles: 0°, 10°, 20°, 30°, 45°) and 5 in-plane rotations (0°, 15°, 45°, 90°, 180°) in isolated throwaway worktree `../neogold-spike-marker-detection` cut from `main`.
- **Exact Commands Run:**
  ```bash
  # 1. Create isolated throwaway worktree
  git worktree add ../neogold-spike-marker-detection main
  cd ../neogold-spike-marker-detection

  # 2. Run benchmark script on ArUco DICT_4X4_50 and AprilTag DICT_APRILTAG_36h11
  d:\Project\neogold-android\.venv\Scripts\python.exe tools/bench_spike_markers.py

  # 3. Clean up worktree
  cd ../neogold-android
  git worktree remove ../neogold-spike-marker-detection --force
  ```

## 4. Raw Results
- **Scale x Angle Latency Matrix:**
  ```text
  =====================================================================================
  Scale   | Angle   | ArUco Detected  | ArUco Time (ms)  | AprilTag Det  | AprilTag Time
  =====================================================================================
  0.75x   | 0°      | 4/4 (PASS)      | 20.62 ms         | 4/4 (PASS)    | 93.94 ms     
  0.75x   | 10°     | 4/4 (PASS)      | 21.93 ms         | 4/4 (PASS)    | 114.35 ms    
  0.75x   | 20°     | 4/4 (PASS)      | 22.09 ms         | 4/4 (PASS)    | 125.84 ms    
  0.75x   | 30°     | 4/4 (PASS)      | 22.55 ms         | 4/4 (PASS)    | 136.69 ms    
  0.75x   | 45°     | 4/4 (PASS)      | 22.13 ms         | 4/4 (PASS)    | 115.23 ms    
  1.00x   | 0°      | 4/4 (PASS)      | 26.92 ms         | 4/4 (PASS)    | 107.96 ms    
  1.00x   | 10°     | 4/4 (PASS)      | 26.24 ms         | 4/4 (PASS)    | 131.54 ms    
  1.00x   | 20°     | 4/4 (PASS)      | 27.17 ms         | 4/4 (PASS)    | 130.99 ms    
  1.00x   | 30°     | 4/4 (PASS)      | 21.97 ms         | 4/4 (PASS)    | 124.27 ms    
  1.00x   | 45°     | 4/4 (PASS)      | 24.37 ms         | 4/4 (PASS)    | 113.89 ms    
  1.25x   | 0°      | 4/4 (PASS)      | 27.18 ms         | 4/4 (PASS)    | 113.68 ms    
  1.25x   | 10°     | 4/4 (PASS)      | 24.80 ms         | 4/4 (PASS)    | 146.14 ms    
  1.25x   | 20°     | 4/4 (PASS)      | 24.24 ms         | 4/4 (PASS)    | 92.12 ms     
  1.25x   | 30°     | 2/4 (FAIL)      | 22.52 ms         | 2/4 (FAIL)    | 77.83 ms     
  1.25x   | 45°     | 2/4 (FAIL)      | 19.65 ms         | 2/4 (FAIL)    | 92.69 ms     
  =====================================================================================

  Summary:
  ArUco 4x4   : 13/15 detected (86.7%), Avg Time: 23.62 ms
  AprilTag 36h: 13/15 detected (86.7%), Avg Time: 114.48 ms

  =====================================================================================
  In-plane Rotation Stress Test (Scale 1.0x, Tilt 10°):
  Rotation   0°: ArUco: 4/4 (24.05 ms) | AprilTag: 4/4 (79.45 ms)
  Rotation  15°: ArUco: 4/4 (22.13 ms) | AprilTag: 4/4 (78.99 ms)
  Rotation  45°: ArUco: 2/4 (20.10 ms) | AprilTag: 2/4 (89.45 ms)
  Rotation  90°: ArUco: 4/4 (19.42 ms) | AprilTag: 4/4 (91.01 ms)
  Rotation 180°: ArUco: 4/4 (20.03 ms) | AprilTag: 4/4 (86.44 ms)
  ```

## 5. Verdict
**Confirmed** (ArUco 4x4)

*Rationale:* ArUco 4x4 marker detection achieves an average per-frame detection time of **23.62 ms**, well within the 80 ms budget (under 30% of the budget), enabling smooth 5 fps preview feedback without frame backlog. AprilTag 36h fails the 80 ms budget (114.48 ms average), reinforcing the ADR decision to use ArUco 4x4.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** Confirms the architecture decision in doc 4 Section 15 and `android/feature/camera/src/main/java/org/neogold/camera/CameraPreviewAnalyzer.kt`. No changes required to preview pipeline.
- **Risk Impact:** Resolves clinical UX risk of preview lag or dropped frames on low-end hardware.
- **Next Actions:** Update `docs/4_System_Architecture.md` line 295 from `[Guessing]` to `[Certain]` linking to this spike.

## 7. Affected Doc Lines
- `docs/4_System_Architecture.md:295`:
  Update `[Guessing]` to `[Certain, verified in docs/spikes/2026-10-26-marker-detection-timing.md]`.
  (Reverted, see Section 8.)

## 8. Correction (27 October 2026)

The package A3 review (ADR-018) found that this spike does not support `Confirmed`.

- The timing ran in Python on a laptop at 640x480. It says nothing about the 80 ms budget on a phone, so the claim in doc 4 is `[Likely]` again until a phone run (package D1, devices D1, D2 and D4). Detection with the OpenCV Android library behind `OpenCvMarkerDetector` will be timed there.
- Detection was 13 of 15 (86.7%). Both families found only 2 of 4 markers at 30 and 45 degrees of tilt at scale 1.25, and at 45 degrees of in-plane rotation, and the table itself marks those cases FAIL. The formal pass condition covered time only, so the verdict said nothing about them.
- Section 6 names `android/feature/camera/src/main/java/org/neogold/camera/CameraPreviewAnalyzer.kt`, which does not exist in this repository. The live analyzer is `LiveCardAnalyzer` in `:engine:camera`.
- The choice of ArUco 4x4 rests on the size and 16 KB measurement in ADR-018, not on this spike.
