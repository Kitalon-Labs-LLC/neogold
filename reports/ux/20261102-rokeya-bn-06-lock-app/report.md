# Fresh-Eyes UX Report: `20261102-rokeya-bn-06-lock-app`

| Field | Value |
|---|---|
| **Run ID** | `20261102-rokeya-bn-06-lock-app` |
| **Persona** | `rokeya-nurse` (`bn`) |
| **Task** | `06-lock-app` — Lock the app before you hand the phone to someone. |
| **Device / Target** | `emulator-5554` |
| **Outcome** | **done** |
| **Taps** | 3 taps (13 total `drive.sh` actions) |
| **Time** | 23.4 s |

## 1. Think-Aloud Walkthrough

1. I need to lock the app and any active study session before handing the phone to another staff member. On the home screen there is no lock button, so let me tap Settings.
2. In Settings I see 'Screen lock (PIN/fingerprint)'. Let me tap it to enable screen lock. I also want to be sure Research Mode is locked, so let me scroll down and check Research mode.
3. Research mode is asking for the 6-digit PIN ('Enter 6-digit Research PIN'), which confirms Research Mode is locked, and if I unlock it, the top bar has a 'Lock research mode' icon. Still, having 'Screen lock' in Settings and 'Lock research mode' inside Research Mode is slightly confusing when someone just says 'Lock the app'.

## 2. Friction Points

### FP-01 [Confusing]
- **Screenshot:** `shots/02_settings_lock_toggle.png`
- **What I expected:** A single 'Lock app now' button on the main screen or top bar before handing the phone to someone.
- **What happened:** Had to go into Settings to toggle 'Screen lock (PIN/fingerprint)' and verify separately whether Research Mode was locked.
- **Severity:** `Confusing`

## 3. Main-Session Screen Mapping & Fix Proposals (Build 8)

| Friction ID | Severity | Mapped Screen ID | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |
|---|---|---|---|---|
| `FP-01` | `Confusing` | S09, S23 & R02 (`S09HomeScreen.kt`, `S23SettingsScreen.kt`, `R02StudyHomeScreen.kt`) | Doc 6 Section 8.4 (Settings & App Lock S23/S25) & Section 11 (Research Session Lock R01/R02) | Proposal only: Add a quick 'Lock now' action on `S09` Home (whenever app lock or an unlocked Research session is active) that immediately locks both Research Mode (`R01`) and the app lock screen (`S25`) in one tap before handing the phone to another person. |

