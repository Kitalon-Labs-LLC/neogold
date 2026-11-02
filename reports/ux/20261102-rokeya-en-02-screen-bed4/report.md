# Fresh-Eyes UX Report: `20261102-rokeya-en-02-screen-bed4`

| Field | Value |
|---|---|
| **Run ID** | `20261102-rokeya-en-02-screen-bed4` |
| **Persona** | `rokeya-nurse` (`en`) |
| **Task** | `02-screen-bed4` — Check baby in bed 4; the mother agrees. |
| **Device / Target** | `emulator-5554` |
| **Outcome** | **done with trouble** |
| **Taps** | 12 taps (39 total `drive.sh` actions) |
| **Time** | 70.2 s |

## 1. Think-Aloud Walkthrough

1. I am on the main screen. I see 'New screening', so I will tap it for the baby in bed 4.
2. First it asks if the parent or guardian agreed. The mother agreed, so I tap Yes.
3. Now it asks for postnatal age in hours and gestational age in weeks. Notice the Next button at the bottom is disabled until I pick both.
4. It tells me to hold the card flat on the baby's chest (sternum) and open the camera.
5. It says 'Sternum photo passed' and asks whether to add an optional forehead photo or skip it. I am not sure if skipping makes the result less accurate.
6. I see the result: 'Suggested next step: Recheck in 12–24 hours'. At the bottom there is an optional field for facility register number. Let me type 'Bed 4' first.
7. Oh! Typing 'Bed 4' with a space shows an error: 'Letters, digits, / and - only (no spaces or names)'. On the ward we often write 'Bed 4'! Let me change it to '12/345'.

## 2. Friction Points

### FP-01 [Confusing]
- **Screenshot:** `shots/09_optional_forehead.png`
- **What I expected:** Clear guidance on when a forehead photo is needed vs. when chest alone is enough.
- **What happened:** Offered 'Add forehead photo' and 'Skip forehead photo' without explaining whether skipping affects the recommendation.
- **Severity:** `Confusing`

### FP-02 [Confusing]
- **Screenshot:** `shots/11_register_space_error.png`
- **What I expected:** Being able to type 'Bed 4' with a space or seeing upfront that spaces are rejected.
- **What happened:** Typing 'Bed 4' triggered a validation error blocking Save until I replaced it with '12/345'.
- **Severity:** `Confusing`

