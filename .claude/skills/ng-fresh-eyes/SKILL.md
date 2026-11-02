---
name: ng-fresh-eyes
description: Runs the P28 fresh-eyes first-time user UX agent from an isolated external sandbox, collects raw reports and screenshots in reports/ux/<run-id>/, and (in the main session after user review) maps friction points to screen IDs (S01-S25, R01-R09) and docs/6_UI_UX_Guidelines.md sections as proposals only.
disable-model-invocation: true
argument-hint: "<persona|all> <task|all> [--device <serial>] [--lang <en|bn>]"
---

# Fresh-Eyes First-Time User UX Evaluation (`/ng-fresh-eyes`, P28)

Run the code-blind `fresh-eyes` agent (`.claude/agents/fresh-eyes.md`) as Rokeya (`rokeya-nurse`, in English and Bangla) or Shirin (`shirin-chw`, in Bangla) across supervisor-spoken tasks (`tools/ux/tasks/01-ward-setup.md` to `06-lock-app.md`).

## Hard Constraints
1. **Strict Zero-Knowledge Isolation:** The `fresh-eyes` agent runs from a temporary sandbox directory outside the git repository (`/tmp/neogold-fresh-eyes-<run-id>`) with `omitClaudeMd: true` and `fresh_eyes_guard.py`. It never sees source code, `CLAUDE.md`, git status, resource IDs, class names, package names, or internal screen IDs (`S01`–`S25`, `R01`–`R09`).
2. **Only Two Permitted Tool Actions in Sandbox:**
   - `Bash`: `tools/ux/drive.sh <shot|see|tap|tap-text|type|swipe|back|home|wait|launch>` with zero shell operators (`;`, `|`, `&`, `` ` ``, `$()`, `>`, `<`).
   - `Read`: `.png` files inside the active run's `shots/` folder.
3. **Synthetic & Bench Images Only:** Never use real patient photos. Screening camera tasks use synthetic bench images (`DebugBenchFeeder` in `src/debug/` or a physical bench rig with a phantom and test card).
4. **Stop Gate After First Two Runs:** Stop and ask the owner to read the raw reports in `reports/ux/<run-id>/report.md` before mapping fixes or touching any UI code.
5. **Proposals Only & Governance for Wording:**
   - Draft fixes as proposals only.
   - All wording proposals must pass `docs/6_UI_UX_Guidelines.md` Section 5 (banned-word check — never `diagnose`, `normal`, `safe`, `low risk`, `accurate`, `guaranteed`) and go to the nurse panel (`TC-USE-01` to `TC-USE-05`).
   - Any clinical or result-screen wording (`S17`, `S20`, `S02`–`S04`) also requires sign-off from the clinical lead (`protect_clinical.py`).

## Procedure

### Step 1: Run Persona and Task Set (`tools/ux/run-fresh-eyes.sh`)
Run the requested persona (`rokeya-nurse` or `shirin-chw`) and task (`01-ward-setup` through `06-lock-app`) via `tools/ux/run-fresh-eyes.sh`:
```bash
bash tools/ux/run-fresh-eyes.sh rokeya-nurse 01-ward-setup --lang en
bash tools/ux/run-fresh-eyes.sh rokeya-nurse 02-screen-bed4 --lang en
```
Or run a full pass across both personas (`rokeya-nurse` in `en` and `bn`, `shirin-chw` in `bn`) and all 6 tasks:
- `01-ward-setup` ("Set up the phone for your ward.")
- `02-screen-bed4` ("Check baby in bed 4; the mother agrees.")
- `03-photo-failing` ("The photo keeps failing; get a result or find out why.")
- `04-join-study` ("Join the study with the code your coordinator sent.")
- `05-send-study-data` ("Send today's study data.")
- `06-lock-app` ("Lock the app before you hand the phone to someone.")

Each run writes:
- `reports/ux/<run-id>/report.md` (outcome: `done`, `done with trouble`, or `gave up`; taps and time from `actions.jsonl`; friction points with screenshots and severity: `Blocker`, `Misread risk`, `Confusing`, `Slow`, `Wording`)
- `reports/ux/<run-id>/actions.jsonl`
- `reports/ux/<run-id>/shots/*.png`

### Step 2: Stop Gate (Owner Reads Raw Reports)
After the first two runs (or before mapping fixes on a new run batch), **stop and ask the owner** to inspect the raw `reports/ux/<run-id>/report.md` files before proceeding to Step 3.

### Step 3: Screen Mapping & Fix Proposals (Main Session Only)
Once the owner confirms:
1. In the main session (which has code and doc access), map every friction point in `reports/ux/<run-id>/report.md` to:
   - Exact screen ID (`S01` to `S25`, `R01` to `R09`) and Kotlin Composable / XML resource file.
   - Exact section of `docs/6_UI_UX_Guidelines.md` (Sections 1, 5, 6, 8, 9, 10, 11, or 12).
   - Hazard `H11` in `docs/8_Security_Compliance_Plan.md` whenever severity is `Misread risk`.
2. Draft concrete UI/UX or copy changes **as proposals only** (do not modify clinical strings directly; route wording changes through the banned-word check, nurse panel, and clinical lead).

