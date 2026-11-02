# Fresh-Eyes UX Report: `20261102-rokeya-bn-01-ward-setup`

| Field | Value |
|---|---|
| **Run ID** | `20261102-rokeya-bn-01-ward-setup` |
| **Persona** | `rokeya-nurse` (`bn`) |
| **Task** | `01-ward-setup` — Set up the phone for your ward. |
| **Device / Target** | `emulator-5554` |
| **Outcome** | **done** |
| **Taps** | 12 taps (35 total `drive.sh` actions) |
| **Time** | 66.0 s |

## 1. Think-Aloud Walkthrough

1. I just opened the app for the first time. It asks me to choose between Bangla and English.
2. There is a safety notice saying this only supports screening and does not replace a blood test (TSB). Let me tap Next.
3. Page 3 lists babies who should not be screened with skin photos. I'll tap 'I understand'.
4. Now it shows 'Device Check & Setup' and mentions camera/storage readiness. On English it says 'Hardware Level FULL' which is technical jargon I don't recognize.
5. It asks me to scan the QR code on the back of the colour card packet.
6. Now it wants 3 qualification test photos of the card on a flat surface under ward light. Doing this 3 times in a row feels repetitive.

## 2. Friction Points

### FP-01 [Wording]
- **Screenshot:** `shots/04_device_check.png`
- **What I expected:** সহজ বাংলায় লেখা থাকবে যে ক্যামেরা ও ফ্ল্যাশ ঠিক আছে, কোনো টেকনিক্যাল শব্দ ছাড়াই।
- **What happened:** The device check screen presents technical hardware status ('Hardware Level FULL' / encrypted storage check) without explaining whether the user needs to act.
- **Severity:** `Wording`

### FP-02 [Slow]
- **Screenshot:** `shots/07_qual_photo_1.png`
- **What I expected:** One quick camera check or a clear indicator of why 3 separate photos of the same flat card are needed.
- **What happened:** Had to tap 'Take qualification photo' 3 separate times with the card in the same spot before advancing.
- **Severity:** `Slow`

## 3. Main-Session Screen Mapping & Fix Proposals (Build 8)

| Friction ID | Severity | Mapped Screen ID | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |
|---|---|---|---|---|
| `FP-01` | `Wording` | S05 (`S05DeviceCheckScreen.kt`) | Doc 6 Section 5 (Language, Tone, and Banned Words) & Section 8.1 (Onboarding S01–S08) | Proposal only: Replace technical Camera2 API level text ('Hardware Level FULL') on `S05` with plain ward-friendly wording ('Camera and flash ready' / 'ক্যামেরা ও ফ্ল্যাশ প্রস্তুত'). Must pass `tools/check_strings_banned.py` and nurse panel review (`TC-USE-01`). |
| `FP-02` | `Slow` | S07 (`S07CameraQualificationScreen.kt`) | Doc 6 Section 8.1 (Onboarding S07 Camera Qualification) & Section 1 (60-Second Ward Budget) | Proposal only: Add a short progress subtitle on `S07` ('Taking 3 quick photos to check flash consistency: 1 of 3') or auto-advance across the 3 qualification bursts so the nurse does not have to tap the shutter 3 times manually. |

