---
description: "P30: Feedback Triage Loop (NeoGold work package; plan first, then wait for approval)"
---
# P30: Feedback triage loop (closed-study version)

Phase 11, Agent quality loop. Depends on: P26, P29. Backlog: none (process). Tests: tooling tests in tools/tests/ with synthetic reports.

## Read first
- docs/8_Security_Compliance_Plan.md Sections 6.6, 10, and 12 (including 12.3)
- docs/9_Test_Plan_Strategy.md Section 9
- docs/11_Bug_Defect_Log.md (all of it)
- docs/12_Deployment_Runbook.md Sections 6 and 12
- The privacy rules in CLAUDE.md
- The Google Play Developer Reporting API docs for the current version (vitals crash and ANR rates, error issues and reports)

## Goal
Every report that reaches us gets an answer, a bucket, and either a fix or a handover to the right person. We can also see how long each step took. Clear bugs move fast through a normal PR. Anything clinical, anything about privacy, and anything that is really "I'm confused" goes to a human first.

## Build
1. Sources. Each one is optional and read-only.
   - **Play vitals:** `tools/feedback/play_vitals.py`, using a separate read-only service account (not the upload one).
     - The key path comes from an environment variable and is never in the repo.
     - It pulls crash and ANR rates by version and device, and error clusters with stack traces.
   - **Shared crash reports:** the ones health workers share through the share sheet (doc 8 Section 6.6). I drop them into `feedback/inbox/`, which is gitignored, never committed, and emptied after triage.
   - **GitHub issues** from the doc 11 issue form, using `gh`.
   - **Security reports** from GitHub private vulnerability reporting.
   - **Coordinator email**, only if I connect a mail MCP server. Read only the label I name, and never open attachments.
2. A privacy screen runs before anything is written anywhere.
   - It drops or redacts names, phone numbers, register numbers, dates of birth, and images.
   - If a report contains a baby's photo or a name, don't copy it. Tell me, so I can ask the sender to delete it, and log that the screen fired.
3. Sorting rules in `feedback/RULES.md`. Each report goes in exactly one bucket:
   - **Clinical:** anything that could change what a health worker does for a baby (the doc 9 Section 9.1 flag).
     - File an S1 defect with a hazard link.
     - Draft a note to the clinical lead (doc 8 Section 12.3).
     - An agent never fixes these without the clinical lead.
   - **Privacy or security:**
     - Open an incident record per doc 8 Section 12, with time zero.
     - List the containment steps for me.
     - No public issue.
   - **Clear defect,** with steps or a stack trace:
     - File a defect from the doc 11 template, with severity.
     - For Tier 2 and Tier 3 code, a fix branch and PR may start in the same session.
     - For Tier 0 and Tier 1 code, write a plan only.
   - **Confusion or a request:**
     - Mark it "needs a conversation", with two or three questions for me to ask the site.
     - Suggest a `/ng-fresh-eyes` task for that screen.
   - **Duplicate or noise:** link it to the original, or close it with a reason.
4. Reply drafts: a plain reply to the reporter, in their language (Bangla or English), with no promises about dates. I send it.
5. Timing: `tools/feedback/report_times.py` reads issue and PR timelines and prints the weekly median hours from report to each of these milestones:
   - first response
   - sorted
   - fix merged
   - fix on the closed track

   The targets come from doc 9 Section 9.2 (an S1 is sorted the same day).
6. `.claude/skills/ng-feedback/SKILL.md` (`disable-model-invocation: true`):
   - Pulls the sources, screens them, sorts them, files the results, and drafts the replies.
   - Ends with a short list: what needs me today, what is in progress, and what was closed.
7. Register the package in docs/14 and the CLAUDE.md Commands table.

## Constraints
- No Sentry, and no other third-party crash or analytics SDK (doc 8 Section 6.6, FR-34). This package adds nothing to the app.
- No automatic merge, release, or rollout. "Fast" means the PR is ready the same day; the second approver still merges.
- Real newborn data never goes into issues, reports, or the repository.

## Done when
- A dry run on synthetic reports sorts every one correctly. There is one report per bucket, including one with a planted name and phone number, and the privacy screen catches both planted values.
- `report_times.py` prints a week's table from the test issues.

## Stop and ask me
- Before creating the read-only service account, which I do in Play Console.
- On any clinical or privacy report: stop and report at once.
