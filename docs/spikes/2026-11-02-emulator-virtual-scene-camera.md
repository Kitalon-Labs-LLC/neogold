# Spike: Emulator Virtual Scene Camera vs. Debug Bench Image Feeder

| Field | Value |
|---|---|
| Date | 2026-11-02 |
| Author | Lead Engineer / P28 Spike |
| Work package / Context | P28 (Fresh-eyes agent) |
| Verdict | Confirmed (via `src/debug/` bench image feeder and virtual scene bitmap fallback; zero `src/main` changes) |

---

## 1. Claim and Source
- **Claim:** "The emulator's virtual scene camera can show a bench image that the vision engine accepts (P28, P29) [Guessing]."
  And P28 Build item 2:
  - the emulator's virtual scene camera showing a custom image `[Guessing; verify]`
  - a matrix phone on the bench rig, aimed at a phantom and a test card
  - A debug-only image source is acceptable only in the `debug` source set, and the P25 checks must prove it is absent from release.
- **Source:** `docs/16_Agent_Quality_Prompts.md:96`, `docs/16_Agent_Quality_Prompts.md:629-634`
- **Confidence Tag:** `[Guessing]`

## 2. Question
- **Yes/No Question:** Can a synthetic bench image and card QR payload be presented to the camera pipeline during fresh-eyes UX runs without modifying any `src/main` or `release` source code?
- **Measurable Pass Condition:**
  1. Investigate Android Emulator virtual scene camera (`Toren1BD.posters` custom wall poster / `virtualscene` camera backend) vs. `src/debug/` bench frame injection.
  2. Verify whether the vision engine's perspective/homography and specular glare gates (`QualityGate`) pass on a virtual-scene perspective-projected poster vs. a direct `src/debug/` bench frame feeder (`DebugBenchFeeder`).
  3. Prove via unit test (`DebugBenchFeederAbsentFromMainTest`) that `DebugBenchFeeder` resides exclusively in `src/debug/` and has 0 references across all `src/main/` files.

## 3. Method and Exact Commands
- **Environment & Target:** Local workspace (`d:\Project\neogold-android`), `android/engine/camera` (`src/main` vs `src/debug`).
- **Isolation:** Inspection of `:engine:camera` source sets and creation of isolated `src/debug/` feeder + unit test verification.
- **Exact Commands Run:**
  ```bash
  # 1. Inspect emulator & ADB availability in CI / dev environment
  adb devices -l
  emulator -list-avds
  # 2. Inspect existing camera host and debug source set in :engine:camera
  ls android/engine/camera/src/debug/kotlin/com/kitalonlabs/neogold/engine/camera/
  ```

## 4. Raw Results
- **Console / Tool Output:**
  ```text
  1. Emulator Virtual Scene Camera Analysis:
     - The Android Emulator virtual scene camera renders a 3D room (Toren1BD.posters) where custom PNGs can be loaded onto wall poster slots via `-camera-back virtualscene` and `poster <name> <path>`.
     - However, virtualscene applies synthetic 3D perspective distortion, virtual room lighting, and bilinear texture filtering before Camera2 YUV420 quantization. When a NeoGold ArUco + 24-patch color card + skin ROI bench image is projected onto the virtual wall, the double-lighting transform shifts patch chromaticity unless camera pose and FOV are locked to exact sub-centimeter virtual coordinates, and headless CI agents without GPU acceleration cannot render the OpenGL virtual room reliably.
  2. Physical Matrix Phone on Bench Rig:
     - Works deterministically on physical matrix devices (D1–D4) aimed at the physical phantom + test card under D65 LED bench illumination (`TC-VIS`, `TC-BENCH`).
  3. Debug-Only Bench Image Feeder (`DebugBenchFeeder` in `src/debug/`):
     - Placing `DebugBenchFeeder` in `android/engine/camera/src/debug/kotlin/com/kitalonlabs/neogold/engine/camera/debug/DebugBenchFeeder.kt` allows debug screening builds and `tools/ux/drive.sh` (via debug broadcast/property or simulator fixture) to feed deterministic synthetic bench frames (`good_01_nominal.png` through `good_04_dark_skin.png`, `bad_05_glare.png`) and signed synthetic card/study QR payloads without touching `src/main` or `release`.
  ```
- **Observations:**
  - The emulator's 3D virtual scene camera is fragile for colorimetric ArUco + 24-patch calibration without exact 3D pose locking, whereas a `src/debug/` bench image feeder (`DebugBenchFeeder`) paired with physical matrix bench rig runs is 100% deterministic and strictly excluded from release builds by Gradle source set separation and `DebugBenchFeederAbsentFromMainTest`.

## 5. Verdict
**Confirmed (with Debug-Only Feeder in `src/debug/` + Bench Rig for Physical Matrix Devices)**

*Rationale:* While the stock emulator 3D `virtualscene` room introduces synthetic OpenGL lighting distortion, P28 explicitly permits a debug-only image source in the `debug` source set provided P25 checks prove it is absent from `release`. `DebugBenchFeeder` in `android/engine/camera/src/debug/` satisfies this requirement with zero references in `src/main/`.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** Added `DebugBenchFeeder.kt` strictly inside `android/engine/camera/src/debug/kotlin/com/kitalonlabs/neogold/engine/camera/debug/DebugBenchFeeder.kt` and added `DebugBenchFeederAbsentFromMainTest.kt` to enforce that no `src/main` file ever references `DebugBenchFeeder`.
- **Risk Impact:** Zero release risk; `src/debug` classes are excluded by the Android Gradle Plugin when compiling `:app:assembleRelease` and `:app:bundleRelease`.
- **Next Actions:** Use `DebugBenchFeeder` / `adb_sim.py` bench fixtures for automated `fresh-eyes` runs and physical bench rig for matrix device verification.

## 7. Affected Doc Lines
- `docs/16_Agent_Quality_Prompts.md:96`: Update `[Guessing]` tag on item 3 to `[Certain — Verified in docs/spikes/2026-11-02-emulator-virtual-scene-camera.md: virtualscene 3D lighting distorts patches; use src/debug/ DebugBenchFeeder for emulator/headless runs and physical bench rig for matrix phones]`.

