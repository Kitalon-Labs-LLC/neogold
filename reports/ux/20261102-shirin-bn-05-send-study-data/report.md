# Fresh-Eyes UX Report: `20261102-shirin-bn-05-send-study-data`

| Field | Value |
|---|---|
| **Run ID** | `20261102-shirin-bn-05-send-study-data` |
| **Persona** | `shirin-chw` (`bn`) |
| **Task** | `05-send-study-data` — Send today's study data. |
| **Device / Target** | `emulator-5554` |
| **Outcome** | **done with trouble** |
| **Taps** | 5 taps (17 total `drive.sh` actions) |
| **Time** | 30.6 s |

## 1. Think-Aloud Walkthrough

1. My shift is ending and I need to send today's study data to the server. From the home screen, I have to go into Settings -> scroll down -> Research mode.
2. On Study Home, there is no button called 'Send study data' or 'Upload'. The button is called 'Export Research Data', which sounds like copying files to a computer or USB drive.

## 2. Friction Points

### FP-01 [Wording]
- **Screenshot:** `shots/02_study_home.png`
- **What I expected:** A button labeled 'Send / Upload Study Data' (or 'ডেটা পাঠান / আপলোড করুন').
- **What happened:** The button on Study Home is labeled 'Export Research Data' ('রিসার্চ ডেটা এক্সপোর্ট'), which sounds like USB file export rather than cloud upload.
- **Severity:** `Wording`

## 3. Main-Session Screen Mapping & Fix Proposals (Build 8)

| Friction ID | Severity | Mapped Screen ID | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |
|---|---|---|---|---|
| `FP-01` | `Wording` | R02 & R07 (`R02StudyHomeScreen.kt`, `R07ExportScreen.kt`) | Doc 6 Section 11 (Research Mode R02 & R07) & Section 12 (Bangla Localization) | Proposal only: Rename the `R02` button from 'Export Research Data' ('রিসার্চ ডেটা এক্সপোর্ট') to 'Send / Export Study Data' ('গবেষণার ডেটা পাঠান বা এক্সপোর্ট করুন') so nurses looking to upload shifts over Wi-Fi immediately recognize it. |

