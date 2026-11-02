# Fresh-Eyes UX Report: `20261102-rokeya-en-04-join-study`

| Field | Value |
|---|---|
| **Run ID** | `20261102-rokeya-en-04-join-study` |
| **Persona** | `rokeya-nurse` (`en`) |
| **Task** | `04-join-study` — Join the study with the code your coordinator sent. |
| **Device / Target** | `emulator-5554` |
| **Outcome** | **done with trouble** |
| **Taps** | 9 taps (33 total `drive.sh` actions) |
| **Time** | 59.4 s |

## 1. Think-Aloud Walkthrough

1. My coordinator asked me to join the study using the QR sheet, PIN 258025, and enrollment code 7K2X-M49P-3QW8. Let me look at the home screen.
2. Wait — on this study build home screen, there is no 'Join study' button and no 'Research mode' button! All I see is 'Recent screenings' and a small Settings gear icon in the top corner.
3. Inside Settings, I only see Language, Screen lock, and Colour card status. Where is the study setup? Let me swipe up to scroll down.
4. There it is at the bottom of Settings: 'Research mode'. Let me tap it.
5. Now it asks me to create and confirm a 6-digit Research PIN. I will enter 258025 twice.
6. I am now in 'Research Mode — Study Home', which shows 'New Research Session', 'Pending Lab TSB (1)', and 'Export Research Data'. Where do I enter the enrollment code 7K2X-M49P-3QW8? It's not on Study Home! Let me check 'Export Research Data'.
7. It asks me to scan the Study Enrolment QR again even though I just scanned the Study QR to unlock Research Mode!

## 2. Friction Points

### FP-01 [Confusing]
- **Screenshot:** `shots/01_study_home_empty.png`
- **What I expected:** A visible 'Join Study' or 'Research Mode' button on the main screen when running the study build.
- **What happened:** The home screen has no action buttons at all in the study build; I had to guess and tap the Settings gear icon in the top-right corner.
- **Severity:** `Confusing`

### FP-02 [Slow]
- **Screenshot:** `shots/02_settings_top.png`
- **What I expected:** Research Mode / Study Enrollment visible without scrolling to the bottom of Settings.
- **What happened:** Research mode is hidden below the fold at the bottom of Settings.
- **Severity:** `Slow`

### FP-03 [Confusing]
- **Screenshot:** `shots/05_research_study_home.png`
- **What I expected:** A 'Join study server' or 'Enter enrollment code' button directly on Study Home when the device is not yet enrolled.
- **What happened:** To enter the coordinator's enrollment code, I had to tap 'Export Research Data' even though I haven't collected any data to export yet.
- **Severity:** `Confusing`

## 3. Main-Session Screen Mapping & Fix Proposals (Build 8)

| Friction ID | Severity | Mapped Screen ID | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |
|---|---|---|---|---|
| `FP-01` | `Confusing` | S09 (`S09HomeScreen.kt`) | Doc 6 Section 8.2 (Home Screen S09) & Section 11 (Research Mode UI R01–R09) | Proposal only: When `triageEnabled == false` (the v1.0 study build), show a primary card on `S09` Home ('Open Research Mode' / 'রিসার্চ মোড খুলুন' -> `R01`) instead of leaving `S09` without a primary action button. |
| `FP-02` | `Slow` | S23 (`S23SettingsScreen.kt`) | Doc 6 Section 8.4 (Settings S23) & Section 11 (Research Mode Entry) | Proposal only: Move the 'Research mode' row above the fold in `S23` Settings (or surface it on `S09` Home in study builds) so study nurses do not have to scroll to the bottom of Settings on every shift. |
| `FP-03` | `Confusing` | R02 & R07 (`R02StudyHomeScreen.kt`, `R07ExportScreen.kt`, `R08JoinStudyServerScreen.kt`) | Doc 6 Section 11 (Research Mode R02 Study Home, R07 Export & Sync, R08 Enrollment) | Proposal only: When the device is not yet enrolled with the study server (`!isEnrolled`), show a direct banner/button on `R02` Study Home ('Join study server / Enter enrollment code' -> `R08`) rather than requiring the user to tap 'Export Research Data' (`R07`) first. |

