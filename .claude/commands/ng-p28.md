---
description: "P28: Fresh-Eyes Agent (NeoGold work package; plan first, then wait for approval)"
---
# P28: Fresh-eyes agent (a first-time user who can't see the code)

Phase 11, Agent quality loop. Depends on: P23 (camera spike), P25. Backlog: none (process). Tests: tooling and hook tests; feeds TC-USE-01 to TC-USE-03 preparation but does not replace them.

## Read first
- docs/2_PRD.md Section 5 (personas) and Section 6 (J1 to J4)
- docs/6_UI_UX_Guidelines.md Sections 1, 8, 9, 10, 11, and 12
- docs/9_Test_Plan_Strategy.md Section 13, and docs/10_Test_Cases.md Section 18
- The Claude Code docs for the installed version: subagent `tools`, `hooks`, and `omitClaudeMd`, plus starting a session as an agent (`--agent`) in headless mode (`-p`)

## Goal
An agent that uses NeoGold the way Rokeya or Shirin would on day one. It sees only the screen and can only tap, type, and swipe. It reports where a new health worker would get stuck, misread something, or give up. It can't read the code or the docs, because then it would already know how the app works.

## Build
1. `tools/ux/drive.sh` is the agent's only way to use the phone. It works over `adb` and offers these commands:

   | Command | What it does |
   |---|---|
   | `shot <name>` | Saves a screenshot into the run folder and prints its path |
   | `see` | Lists visible text, content descriptions, bounds, and clickable flags from a UI dump, with resource IDs, class names, and package names removed |
   | `tap <x> <y>`, `tap-text "<label>"` | Taps a point or a visible label |
   | `type "<text>"` | Types digits and Latin letters (enough for codes and numbers; NeoGold has no free-text fields) |
   | `swipe up/down/left/right` | Swipes |
   | `back`, `home`, `wait <seconds>`, `launch` | Basic navigation |

   - Validate arguments strictly, and never pass input through shell evaluation.
   - Append every action to `actions.jsonl` with a timestamp.
2. Camera: find a way to put a bench image in front of the camera without touching release code. Check the options with `/ng-spike`:
   - the emulator's virtual scene camera showing a custom image
   - a matrix phone on the bench rig, aimed at a phantom and a test card

   A debug-only image source is acceptable only in the `debug` source set, and the P25 checks must prove it is absent from release. If neither option works, stop and ask me.
3. `.claude/agents/fresh-eyes.md`:
   - Tools: Bash and Read only, with `omitClaudeMd: true`.
   - Frontmatter PreToolUse hooks allow only two things and deny everything else:
     - Bash, when the command is `tools/ux/drive.sh` with no shell operators
     - Read, for PNG files inside the current run's `shots/` folder
   - Its system prompt covers who it is (from the persona file), that it has never seen this app, that it thinks aloud briefly before each action, and what to report.
4. Personas in `tools/ux/personas/`:
   - `rokeya-nurse.md`, run in English and in Bangla
   - `shirin-chw.md`, run in Bangla

   Write them from PRD Section 5, describing what each person knows and how she works. Say nothing about the app's design.
5. Tasks in `tools/ux/tasks/`, written the way a supervisor would say them, with no screen names:
   - "Set up the phone for your ward."
   - "Check baby in bed 4; the mother agrees."
   - "The photo keeps failing; get a result or find out why."
   - "Join the study with the code your coordinator sent."
   - "Send today's study data."
   - "Lock the app before you hand the phone to someone."
6. `tools/ux/run-fresh-eyes.sh <persona> <task> [--device <serial>]`:
   - Installs the right build:
     - screening tasks: the debug build with the synthetic config and `triageEnabled: true`
     - research tasks: the study build
   - Resets app data and creates `reports/ux/<run-id>/`.
   - Builds a sandbox folder outside the repository. It holds only `drive.sh`, the agent file and its hook scripts, the persona, the task, and `shots/`.
   - Starts a separate headless session as the fresh-eyes agent from that sandbox, given only the persona and task text. Working outside the repository means no CLAUDE.md, git status, or file names reach it, and no parent session can leak hints.
   - Copies the report and screenshots back to `reports/ux/<run-id>/`, then deletes the sandbox.
   - Verify the flags for the installed version. If a separate session can't be started, run it as a subagent and pass the two files word for word and nothing else.
7. The report, `reports/ux/<run-id>/report.md`:
   - The outcome: done, done with trouble, or gave up.
   - Taps and time per task, from actions.jsonl.
   - Each friction point with its screenshot, what it expected, what happened, and a severity: Blocker, Misread risk, Confusing, Slow, or Wording.
   - Any moment where it misunderstood a result or the next step counts as a Misread risk and links to H11.
8. `.claude/skills/ng-fresh-eyes/SKILL.md` (`disable-model-invocation: true`):
   - Runs a persona and task set.
   - Then, in the main session with code access, maps each friction point to a screen ID (S01 to S25, R01 to R09) and a doc 6 section, and drafts fixes as proposals only.
   - Wording changes go through doc 6, the banned-word check, and the nurse panel. Clinical wording also goes through the clinical lead.
9. Register the package in docs/14 and the CLAUDE.md Commands table.

## Constraints
- The fresh-eyes agent never sees source code, docs, CLAUDE.md, resource IDs, or test names. Prove it in the PR with a test run where each of these attempts is denied: `cat`, `ls`, a Read on a source file, and `drive.sh see; cat x`.
- Synthetic and bench images only.
- This doesn't replace TC-USE-01 to TC-USE-05. It finds dead ends and confusing words before the nurse panel. It can't tell you about ward light, a crying baby, or whether a nurse trusts the result.

## Done when
- Both personas have run every task once on the emulator.
- Rokeya has run the screening task on one matrix phone.
- The reports are committed, with friction points mapped to screens.

## Stop and ask me
- After the first two runs, before mapping fixes. I read the raw reports.

