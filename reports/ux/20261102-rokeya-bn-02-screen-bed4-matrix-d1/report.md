# Fresh-Eyes UX Report: `20261102-rokeya-bn-02-screen-bed4-matrix-d1`

| Field | Value |
|---|---|
| **Run ID** | `20261102-rokeya-bn-02-screen-bed4-matrix-d1` |
| **Persona** | `rokeya-nurse` (`bn`) |
| **Task** | `02-screen-bed4` — Check baby in bed 4; the mother agrees. |
| **Device / Target** | `D1-galaxy-a05-matrix` |
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

## 3. Main-Session Screen Mapping & Fix Proposals (Build 8)

| Friction ID | Severity | Mapped Screen ID | Doc 6 Section / Hazard | Proposed Fix (Proposal Only) |
|---|---|---|---|---|
| `FP-01` | `Confusing` | S18 (`S18ForeheadPromptScreen.kt`) | Doc 6 Section 8.3 (Capture & Quality Gate S15–S19) | Proposal only: Clarify on `S18` that the chest (sternum) photo is sufficient for a complete result and the forehead photo is an optional extra check ('Chest photo is complete. You can go straight to the result or add an optional forehead photo'). Route through nurse panel and clinical lead. |
| `FP-02` | `Confusing` | S20 (`S20ResultScreen.kt`) | Doc 6 Section 9 (Result Screen S20) & Doc 8 Section 3 (Privacy Guardrail — No Patient Names) | Proposal only: Keep the no-letters-plus-spaces privacy guardrail (which prevents typing a baby's name), but auto-convert spaces between 'Bed' and a number into a hyphen (`Bed 4` -> `BED-4`) or show the example `e.g. 12/345 or BED-4` directly inside the placeholder label on `S20`. |

