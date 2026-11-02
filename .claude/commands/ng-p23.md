---
description: "P23: Spike and Assumption Check (NeoGold work package; plan first, then wait for approval)"
---
# P23: Spike and assumption check

Phase 11, Agent quality loop. Depends on: P00. Backlog: none (process). Tests: tooling tests in tools/tests/ (no TC IDs; this package adds process, not product requirements).

## Read first
- CLAUDE.md
- docs/16_Agent_Quality_Prompts.md Sections 0, 1, and 4
- Every `[Likely]` and `[Guessing]` tag in docs/2 to docs/15 (grep for them; don't read every doc end to end)
- The Claude Code docs on skills and subagents for the installed version (`claude --version`). Don't write frontmatter from memory.

## Goal
Two commands:
- `/ng-spike "<claim>"` tests one claim from scratch in a throwaway worktree and records the evidence, so no decision rests on "the docs say so" or "a video said so".
- `/ng-assumptions` lists the unverified claims the code now depends on, ranked by what breaks if they turn out to be wrong.

## Build
1. `.claude/skills/ng-spike/SKILL.md` (`disable-model-invocation: true`; the argument is the claim in quotes). The skill must follow these steps:
   a. Restate the claim as a yes-or-no question with a measurable pass condition. Name where the claim came from: doc and line, vendor page, video, or model output. If there is no measurable condition, stop and ask me.
   b. Create a throwaway worktree `../neogold-spike-<slug>` from `main`, or use a subagent with `isolation: worktree`. Spike code lives only there.
   c. Build the smallest thing that answers the question. Run it on the target where it matters: both ABIs, API 26 and API 36 emulators, or a matrix phone if I connect one.
   d. Write `docs/spikes/YYYY-MM-DD-<slug>.md` from the template with these fields:
      - claim and source
      - question
      - method and exact commands
      - raw results
      - verdict: Confirmed, Refuted, or Inconclusive
      - what the verdict changes
      - the doc lines that carry the claim
   e. Delete the worktree. Commit only the record, on a branch `spike/<slug>`. The PR also updates the tag in the doc: either `[Certain]` with a link to the record, or a correction.
2. `.claude/skills/ng-assumptions/SKILL.md` (`disable-model-invocation: true`):
   - Grep the tags.
   - For each one, decide three things:
     - Does code depend on it? Cite the file and line.
     - Does a test already prove it? Cite the test.
     - What is the impact if it is wrong: clinical, privacy, security, cost, or schedule?
   - Write `reports/assumptions/YYYY-MM-DD.md`: a table sorted by impact, with the top 10 unproven claims as ready-to-run `/ng-spike` lines.
3. `docs/spikes/TEMPLATE.md` and `docs/spikes/README.md`. The README says what a spike is and states the rule that spike code never merges.
4. Register the package:
   - Add a P23 row to docs/14 Sections 1 and 2, with a new Phase 11 "Agent quality loop".
   - Add docs/16_Agent_Quality_Prompts.md to the CLAUDE.md "Source of truth" table.
   - Add both skills to the CLAUDE.md Commands table.

## Constraints
- A spike never changes `android/`, `backend/`, `training/`, or `config/` on a branch that can merge.
- A spike that needs a new tool or library asks me first, like any dependency.
- A verdict needs evidence someone else can rerun. "Seems to work" is Inconclusive.
- Never use real newborn data in a spike.

## Done when
- `/ng-assumptions` has run once and its report is committed.
- One spike has run end to end on a claim from that report (I pick it in the plan), and its PR updates the doc tag.
- The first spikes in docs/16 Section 4 are listed in the report, even if they haven't run yet.

## Stop and ask me
- Before running the first spike, with the top 10 list.

