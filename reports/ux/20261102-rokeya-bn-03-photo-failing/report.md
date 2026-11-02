# Fresh-Eyes UX Report: `20261102-rokeya-bn-03-photo-failing`

| Field | Value |
|---|---|
| **Run ID** | `20261102-rokeya-bn-03-photo-failing` |
| **Persona** | `rokeya-nurse` (`bn`) |
| **Task** | `03-photo-failing` — The photo keeps failing; get a result or find out why. |
| **Device / Target** | `emulator-5554` |
| **Outcome** | **done with trouble** |
| **Taps** | 15 taps (23 total `drive.sh` actions) |
| **Time** | 41.4 s |

## 1. Think-Aloud Walkthrough

1. I need to check a baby beside a bright window where the photo keeps failing.
2. It says 'Photo problem — Attempt 1 of 3 — Too much glare. Move away from the window.' Let me try retaking.
3. Attempt 2 of 3 also failed because of window glare. Let me try one more time.
4. After the 3rd failed photo, it jumped straight to a screen saying 'Suggested next step: Blood test today' and 'Camera could not get a clear photo after 3 tries. Order a laboratory blood test (TSB) today.' At first glance, the big 'Blood test today' banner looks identical to a high-jaundice screening result, which could make a busy worker think the app measured high bilirubin rather than a camera failure (H11).

## 2. Friction Points

### FP-01 [Misread risk] (Links to **H11** — Misread / Over-Reliance on Screening Output)
- **Screenshot:** `shots/03_fallback_blood_test_today.png`
- **What I expected:** A distinct heading clearly separating 'Camera could not read the card — do a blood test to be safe' from a measured high-bilirubin result.
- **What happened:** After 3 glare failures, the screen shows the same top headline ('Suggested next step: Blood test today') as a high-risk measurement, creating a Misread risk (linked to H11) that a worker tells the mother the baby has severe jaundice when only the camera failed.
- **Severity:** `Misread risk` (Links to **H11** — Misread / Over-Reliance on Screening Output)

## 3. Main-Session Screen Mapping & Fix Proposals (Build 8)

| Friction ID | Severity | Mapped Screen ID | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |
|---|---|---|---|---|
| `FP-01` | `Misread risk` | S17 -> S20 (`S17QualityFailureScreen.kt`, `S20ResultScreen.kt`) | Doc 6 Section 9 (Result Screen S20 — `QUALITY_FALLBACK`), Section 10 (Error & Recovery States), and Hazard `H11` | Proposal only (Links to **H11**): When `S20` is reached via `QUALITY_FALLBACK` (after 3 failed photo attempts on `S17`), display a prominent top banner ('No photo result — camera could not read the card' / 'ছবি থেকে ফলাফল পাওয়া যায়নি') above 'Suggested next step: Blood test today', so health workers never confuse an unreadable photo fallback with a measured high-bilirubin result. Requires clinical lead sign-off (`protect_clinical.py`) and nurse panel verification (`TC-USE-02`). |

