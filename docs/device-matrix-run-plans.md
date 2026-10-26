# NeoGold Device-Matrix Run Plans (D1 to D7)

| Field | Value |
|---|---|
| Milestone | v1.0 Closed Research Release Candidate (Phase 10, PRD D10) |
| Document | `docs/device-matrix-run-plans.md` |
| Author | Kitalon Labs / Test Lead |
| Reference | `docs/9_Test_Plan_Strategy.md` Section 4.2 & Section 11 |

---

## 1. Overview & Strategy

The NeoGold physical test matrix consists of seven hardware tiers (D1 through D7) reflecting the real smartphone landscape in rural and district-level Bangladesh:

| ID | Class | Target OS / API | RAM Floor | Primary Verification Focus |
|---|---|---|---|---|
| **D1** | Transsion Entry (TECNO, Infinix, itel) | Android 13–15 | 3–4 GB | Reference performance class (NFR-02); HiOS / XOS aggressive process management |
| **D2** | Xiaomi Entry (Redmi A/C series) | Android 14–15 | 3–4 GB | HyperOS / MIUI background limits; secondary reference class |
| **D3** | Samsung Galaxy A06 | Android 14–15 | 4 GB | High-volume hospital procurement; One UI security & App Lock compatibility |
| **D4** | Android Go Floor (itel A70, Walton Go) | Android 11–14 Go | 2 GB | Hard memory and latency ceiling (NFR-03, NFR-04b); 2-frame burst adaptation |
| **D5** | Older Phone | Android 10–11 (API 29–30) | 3 GB | Legacy Camera2 HAL behavior; Bengali complex font conjuncts rendering |
| **D6** | Local Assembly (Symphony, Walton) | Android 11–13 | 2–4 GB | Locally assembled sensor calibration; optical qualification |
| **D7** | 16 KB Page Alignment Device | Android 15+ (16 KB kernel) | Any | ELF LOAD segment alignment verification for native `.so` binaries (Gate S4) |

---

## 2. Detailed Run Plans per Device

### D1: Transsion Entry (TECNO Spark 20 / Infinix Hot 30)
- **Scripted Execution:**
  - `maestro test android/maestro/j1_onboarding_qualification_flow.yaml`
  - `maestro test android/maestro/j4_research_capture_flow.yaml`
  - `maestro test android/maestro/research_enrollment_flow.yaml`
  - `maestro test android/maestro/research_upload_now_flow.yaml`
  - `maestro test android/maestro/research_export_flow.yaml`
  - `python tools/reliability/adb_kill_loop.py -n 50`
  - `python tools/reliability/storage_fill.py --target-mb 150`
  - `python tools/reliability/clock_changes.py --test all`
- **Manual Hand-Tested Execution:**
  - TC-DEV-09: Verify S05 opens Transsion Phone Manager / HiOS Auto-start settings.
  - TC-PERF-05: 10 manual screening journeys (J2) with physical card timed with stopwatch (target: < 60 s).
  - TC-PERF-06: 20 consecutive screenings from 100% battery (target: < 1% drain per screening).
  - TC-REL-07: Overnight survival test under default HiOS battery restrictions (rechecks preserved).

### D2: Xiaomi Entry (Redmi 13C / Redmi A2)
- **Scripted Execution:**
  - `maestro test android/maestro/j1_onboarding_qualification_flow.yaml`
  - `maestro test android/maestro/j4_research_capture_flow.yaml`
  - `maestro test android/maestro/screening_capture_flow.yaml`
  - `python tools/reliability/adb_kill_loop.py -n 50`
- **Manual Hand-Tested Execution:**
  - TC-DEV-09: Verify HyperOS / MIUI battery optimization deep link.
  - TC-DEV-05: Force HDR / AI beauty filter mode in stock camera before opening NeoGold -> confirm Camera Qualification flags uniformity and classifies as `RESEARCH_ONLY`.
  - TC-PERF-01: 30 capture-to-result benchmark runs (target: p50 < 4 s, p95 < 10 s).

### D3: Samsung Galaxy A06
- **Scripted Execution:**
  - `maestro test android/maestro/j1_onboarding_qualification_flow.yaml`
  - `maestro test android/maestro/j4_research_capture_flow.yaml`
  - `maestro test android/maestro/research_upload_now_flow.yaml`
- **Manual Hand-Tested Execution:**
  - TC-PRIV-01: App lock validation with Samsung biometric prompt (fingerprint & PIN fallback).
  - TC-PRIV-02: Screenshot blocking in Recents screen and via hardware buttons (Power + Vol Down).
  - One UI battery protection mode compatibility.

### D4: Android Go Floor (itel A70, 2 GB RAM)
- **Scripted Execution:**
  - `maestro test android/maestro/screening_capture_flow.yaml` (validates low-RAM 2-frame burst adaptation).
  - `python tools/reliability/adb_kill_loop.py -n 25`
  - `python tools/reliability/storage_fill.py --target-mb 120`
- **Manual Hand-Tested Execution:**
  - TC-PERF-02: 30 consecutive captures; verify 0 Out-Of-Memory (OOM) kills.
  - TC-PERF-03: Perfetto memory profile during analysis (peak memory < 120 MB).
  - Manual battery pull checklist: 10 physical power-loss tests during save (TC-RSH-15).

### D5: Older Phone (Android 10 / API 29)
- **Scripted Execution:**
  - `maestro test android/maestro/device_qualification_flow.yaml`
  - `maestro test android/maestro/research_export_flow.yaml`
- **Manual Hand-Tested Execution:**
  - TC-A11Y-02: Visual inspection of Bangla conjuncts and vowel marks (Noto Sans Bengali) on Android 10 text rasterizer.
  - TC-DEV-06: Verification of Camera2 `LEGACY` hardware level without AE/AWB locks (passes color tests -> `QUALIFIED`).
  - Storage Access Framework (SAF) document tree export validation on Android 10.

### D6: Local Assembly (Symphony / Walton)
- **Scripted Execution:**
  - `maestro test android/maestro/device_qualification_flow.yaml`
  - `maestro test android/maestro/screening_capture_flow.yaml`
- **Manual Hand-Tested Execution:**
  - Optical calibration check with physical NeoGold Card Batch 2026-A under mixed hospital lighting.
  - Local Walton / Symphony battery management deep-link verification.

### D7: 16 KB Page Alignment Device (Android 15 / 16 KB Kernel Image)
- **Scripted Execution:**
  - `bash tools/check_16kb.sh android/app/build/intermediates/merged_native_libs/release/out/lib`
  - Automated smoke test launching NeoGold universal APK on 16 KB emulator image (TC-SEC-04).
- **Manual Hand-Tested Execution:**
  - Verify zero crash logs or linker errors (`SIGSEGV` or `PAGE_SIZE` mismatches) upon loading native vision shared objects.
