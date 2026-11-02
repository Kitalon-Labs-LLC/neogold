# Fresh-Eyes UX Report: `20261102-rokeya-en-01-ward-setup`

| Field | Value |
|---|---|
| **Run ID** | `20261102-rokeya-en-01-ward-setup` |
| **Persona** | `rokeya-nurse` (`en`) |
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
- **What I expected:** A plain message in my language saying the phone camera and flash work.
- **What happened:** The device check screen presents technical hardware status ('Hardware Level FULL' / encrypted storage check) without explaining whether the user needs to act.
- **Severity:** `Wording`

### FP-02 [Slow]
- **Screenshot:** `shots/07_qual_photo_1.png`
- **What I expected:** One quick camera check or a clear indicator of why 3 separate photos of the same flat card are needed.
- **What happened:** Had to tap 'Take qualification photo' 3 separate times with the card in the same spot before advancing.
- **Severity:** `Slow`

