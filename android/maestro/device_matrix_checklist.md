# NeoGold Physical Device Test Checklist & Matrix (P09 / P22)

Target Devices:
- **D1**: Transsion Entry (TECNO Spark 10/20, Infinix Hot 30/40, itel A/P series) — Android 13–15, 3–4 GB RAM, HiOS/XOS
- **D2**: Xiaomi Entry (Redmi 13C/14C, Redmi A2/A3) — Android 14–15, 3–4 GB RAM, MIUI/HyperOS
- **D4**: Android Go Floor (Any 2 GB RAM Android Go Edition phone) — Android 11–14 Go

---

## 1. Test Automation Execution with Maestro

To run automated flows on a connected device via Maestro (use the debug build: its screen test tags are exposed as ids, a release build does not expose them):

```bash
# 1. Device check & 3-photo camera qualification
maestro test -e APP_ID=com.kitalonlabs.neogold.debug android/maestro/device_qualification_flow.yaml

# 2. Live preview, card detection & screening burst auto-capture
maestro test -e APP_ID=com.kitalonlabs.neogold.debug android/maestro/screening_capture_flow.yaml
```

---

## 2. Test Cases Checklist (TC-DEV-01 to TC-DEV-10, TC-CAP-01, TC-CAP-15)

| Test ID | Description | Requirement | D1 (TECNO/Infinix) | D2 (Xiaomi) | D4 (Android Go 2GB) | Pass Criteria |
|---|---|---|:---:|:---:|:---:|---|
| **TC-DEV-01** | Device check capabilities | FR-03, NG-004 | [ ] | [ ] | [ ] | RAM MB, `isLowRamDevice` (true on D4), Camera HW level (`LEGACY`, `LIMITED`, `FULL`), AE/AWB locks recorded. |
| **TC-DEV-02** | Repeatability pass | FR-04, NG-007, H5 | [ ] | [ ] | [ ] | Repeatability $\Delta E_{00} < 1.5$ across 3 photos on bench at 4000 K. |
| **TC-DEV-03** | Uniformity pass | FR-04, H5 | [ ] | [ ] | [ ] | Max duplicate corner patch $\Delta E_{00} < 2.0$ across 3 photos. |
| **TC-DEV-04** | Accuracy pass | FR-04, H5 | [ ] | [ ] | [ ] | Mean calibration residual $\Delta E_{00} < 3.0$ vs batch reference. |
| **TC-DEV-05** | Local processing fails phone | FR-04, H5 | [ ] | [ ] | [ ] | Force HDR/beauty mode or spot light -> Uniformity $> 2.0$ -> Status is `RESEARCH_ONLY`. |
| **TC-DEV-06** | Missing AE/AWB lock does not fail | FR-11, ADR-012 | [ ] | [ ] | [ ] | `LEGACY` camera without AE/AWB lock passes 3 color tests -> Status is `QUALIFIED`. |
| **TC-DEV-07** | Re-qualification prompt | NG-044 | [ ] | [ ] | [ ] | Triggered after 30 days (`currentTime - qualifiedAt > 30d`) or OS build fingerprint change. |
| **TC-DEV-08** | Qualification is audited | NFR-10 | [ ] | [ ] | [ ] | `DEVICE_QUALIFIED` or `DEVICE_RESEARCH_ONLY` audit event recorded with metrics only (no PII). |
| **TC-DEV-09** | Battery settings guidance | FR-03, NFR-12 | [ ] | [ ] | [ ] | Brand-specific steps shown (HiOS/XOS on D1, HyperOS/MIUI on D2, generic on D4) with deep link. |
| **TC-DEV-10** | Supported device list | NG-046 | [ ] | [ ] | [ ] | Known models display "tested"; unlisted phones run qualification and show "untested". |
| **TC-CAP-01** | Capture only with card in view | FR-10, NG-008 | [ ] | [ ] | [ ] | Auto-capture fires only when all 4 fiducial markers detected and steady for 1.0 s. |
| **TC-CAP-15** | Capture metadata recorded | FR-11, NG-009, NFR-10 | [ ] | [ ] | [ ] | `CaptureMetadata` records ISO, exposure time, AE/AWB lock states, gains, and camera HW level. |

---

## 3. Manual Verification Procedures

### Check 1: Zero Image Persistence Verification (Screening Mode)
Screening mode MUST NEVER write any image files (`.jpg`, `.jpeg`, `.png`, `.webp`, `.dng`) to persistent storage or DCIM.

Run via ADB after performing a screening capture:
```bash
# Verify no image files in app private storage
adb shell find /data/data/com.kitalonlabs.neogold/files -type f \( -name "*.jpg" -o -name "*.jpeg" -o -name "*.png" \)

# Verify no image files in external files directory
adb shell find /sdcard/Android/data/com.kitalonlabs.neogold/files -type f \( -name "*.jpg" -o -name "*.jpeg" -o -name "*.png" \)

# Verify no image files in MediaStore / DCIM
adb shell find /sdcard/DCIM -type f -mmin -5
```
**Expected Output:** Zero results found.

---

### Check 2: Camera Burst Frame Count & Memory Adaptation
- **On D1 & D2 (3–4 GB RAM):** Standard burst captures **3 frames** into memory; selects the sharpest frame using Laplacian variance.
- **On D4 (2 GB RAM, Android Go):** Low-RAM mode dynamically reduces burst to **2 frames** to conserve memory headroom.
- Downsampled decoding targets $\sim 1600\text{ px}$ on the long edge directly in-memory; bitmaps are recycled immediately after feature extraction.

---

### Check 3: Brand Battery Optimization Deep Link Validation
1. **D1 (TECNO / Infinix / itel):**
   - Tap "Open Settings" on S05.
   - Verifies intent opens HiOS/XOS `PowerCenter` or `com.transsion.phonemanager`.
   - Instructions describe: Settings -> Battery -> Battery Optimization / Auto-start -> Enable NeoGold.
2. **D2 (Xiaomi / Redmi / POCO):**
   - Tap "Open Settings" on S05.
   - Verifies intent opens `com.miui.powerkeeper` or `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
   - Instructions describe: Settings -> Battery -> NeoGold -> No restrictions; Autostart enabled.
3. **D4 (Android Go / Generic):**
   - Tap "Open Settings" on S05.
   - Verifies intent opens standard AOSP battery optimization exemption dialog.

---

### Check 4: Research Mode JPEG Sanitizer Allow-List Audit
When research mode captures are exported, verify with the allow-list validator:
```bash
# Validates that output JPEG contains ONLY:
# SOI, APP0 (JFIF without thumbnail), APP1 (Exif with orientation only), DQT, SOF, DHT, DRI, SOS, EOI
# Strictly strips: GPS, DateTime, MakerNotes, XMP, and thumbnails.
```
