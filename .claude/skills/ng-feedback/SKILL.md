---
name: ng-feedback
description: Ingests user reports, Android Vitals, and shared crash reports; screens for privacy; sorts into RULES.md buckets; drafts replies; and tracks turnaround times.
disable-model-invocation: true
argument-hint: "[--inbox <dir>] [--vitals] [--dry-run]"
---

# NeoGold Feedback Triage Loop (`/ng-feedback`, P30)

Ingests feedback, crashes, and defect reports from all active sources, runs a mandatory privacy screen, sorts each report into **exactly one bucket** per `feedback/RULES.md`, drafts polite replies, and updates the feedback turnaround time metrics.

## Hard Constraints
1. **Privacy Screen First (Doc 8 Section 3, FR-23):** Every report must pass through `tools/feedback/privacy_screen.py` before any artifact is written or copied.
   - If a baby's photo or baby's name is detected: **DO NOT COPY IT.** Immediately alert the human to ask the sender to delete it, and log that the screen fired.
   - Names, phone numbers, register numbers, dates of birth, and images are dropped or redacted.
2. **Clinical Safety Veto (Doc 9 Section 9.1, Doc 8 Section 12.3):** Any report that could alter what a health worker does for a baby is flagged `Clinical: Yes`, treated as `S1 Critical`, linked to a hazard (H1 to H11), and reported to the Clinical Lead. An agent **never** fixes a clinical issue without the Clinical Lead.
3. **Privacy / Security Containment:** Security reports trigger an incident record per doc 8 Section 12 with time zero and a 4-hour containment checklist. **Never** open a public GitHub issue for privacy or security reports.
4. **No Third-Party SDKs (Doc 8 Section 6.6, FR-34):** No Sentry, ACRA, or external analytics SDKs. Sources are limited to Play Developer Reporting API, shared crash reports (`feedback/inbox/`), GitHub issues, and coordinator emails.
5. **No Date Commitments:** Reply drafts must never promise a delivery or fix date to the reporter.

---

## Procedure

### Step 1: Ingest Sources
Run the triage orchestrator against the active sources:
```bash
# Process shared crash reports in feedback/inbox/
python3 tools/feedback/triage.py --inbox feedback/inbox/

# For offline dry-run or testing with synthetic reports
python3 tools/feedback/triage.py --synthetic-reports testdata/feedback/synthetic_reports.json
```

If Google Play Vitals are configured:
```bash
python3 tools/feedback/play_vitals.py --output-md feedback/triaged/play_vitals.md
```

### Step 2: Privacy Verification
Ensure `feedback/privacy_screen.log` records any events where the screen fired. If any report was blocked due to newborn identifiers, notify the lead immediately:
`"Report <ID> blocked: Contains baby name or photo. Sender must be asked to delete it."`

### Step 3: Handle Buckets per `feedback/RULES.md`
- **Clinical:** File S1 defect in `docs/11_Bug_Defect_Log.md`, write a diagnostic plan only, and draft a note to the Clinical Lead.
- **Privacy or Security:** File an incident record in `feedback/incidents/`, list containment steps for the lead, and keep strictly private.
- **Clear Defect:** File defect per doc 11 template. If Tier 2 or Tier 3 code, prepare fix branch and PR. If Tier 0 or Tier 1 code, draft a reproduction plan only.
- **Confusion or Request:** Mark "needs a conversation", formulate 2-3 questions for the site coordinator, and recommend `/ng-fresh-eyes`.
- **Duplicate or Noise:** Link to original bug ID or close with rationale.

### Step 4: Turnaround Time Reporting
Generate weekly turnaround time metrics and check compliance with doc 9 Section 9.2:
```bash
python3 tools/feedback/report_times.py --input feedback/timelines.json
```

### Step 5: Executive Session Summary
Output a short, unambiguous summary:
1. **What Needs Me Today:**
   - Any Clinical safety alerts
   - Any Privacy/Security incident containment actions
   - Clarifying questions to ask study sites
2. **What Is In Progress:**
   - Active bug investigations or Tier 2/3 fix branches
3. **What Was Closed / Duplicate:**
   - Filtered duplicates and noise

