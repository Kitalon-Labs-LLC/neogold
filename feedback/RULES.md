# NeoGold Feedback Triage Rules (`feedback/RULES.md`)

This document defines the deterministic rules for sorting all incoming reports into **exactly one bucket**.
Every report that reaches NeoGold gets an answer, a bucket, and either a fix or a handover to the right person.

Related documents:
- `docs/8_Security_Compliance_Plan.md` Sections 6.6, 10, 12, 12.3
- `docs/9_Test_Plan_Strategy.md` Section 9 (Defect Management & Severity)
- `docs/11_Bug_Defect_Log.md` (Fields, status workflow, defect template)
- `docs/12_Deployment_Runbook.md` Sections 6 (Halt & Roll-forward) and 12 (Monitoring)
- `quality/tiers.yaml` (Tier definitions for automated code remediation)

---

## 0. Fundamental Guardrails

1. **Privacy Screen First:** Every incoming report must pass through `tools/feedback/privacy_screen.py` before any text or artifact is written to disk or issues.
   - Any report containing a baby's name or photo is **never copied**. The screening logs an alert, halts copying, and prompts the technical lead to ask the sender to delete it immediately.
   - Phone numbers, dates of birth, register numbers, and other identifiers are permanently redacted.
2. **Exactly One Bucket:** Every report belongs to exactly one of the five buckets below. If a report touches multiple areas (e.g. usability question + clinical discrepancy), the higher-precedence bucket applies (Precedence: Clinical > Privacy/Security > Clear Defect > Confusion/Request > Duplicate/Noise).
3. **No Automatic Merge or Public Release:** An agent may prepare a fix or branch, but a human must review and the second approver must merge.
4. **No Third-Party Crash SDKs:** NeoGold uses Android Vitals, shared crash reports (`feedback/inbox/`), GitHub issues, and coordinator emails. No external crash SDKs (FR-34, doc 8 Section 6.6).

---

## 1. Bucket Definitions and Actions

### Bucket 1: Clinical Safety
**Definition:** Anything that could change what a health worker does for a baby (doc 9 Section 9.1).
- Examples:
  - Wrong boundary or threshold applied in triage engine.
  - Recommendation or next-step text differs from clinical guideline.
  - Missed jaundice warning or incorrect danger-sign handling.
  - False negative screening result.
  - Gestational age boundary or age-rounding miscalculation affecting action.
  - Device qualification bypass or unverified card accepted.
- **Mandatory Actions:**
  1. Flag as `Clinical: Yes`.
  2. Treat as severity **`S1 Critical`** regardless of frequency.
  3. Link to relevant Hazard ID (`H1` to `H11`, doc 8 Section 14).
  4. Draft an incident note to the **Clinical Lead** per doc 8 Section 12.3.
  5. File an S1 defect entry formatted per doc 11 Section 7.
  6. **Hard Constraint:** An AI agent **never fixes or creates a pull request for a clinical defect** without explicit direction and sign-off from the Clinical Lead. Write a diagnostic plan only.
  7. **Stop and Alert:** Immediately pause automated batch processing and alert the technical lead.

### Bucket 2: Privacy or Security Incident
**Definition:** Potential data exposure, unauthorized access, cryptographic flaw, secrets leakage, or unredacted patient identifiers.
- Examples:
  - Phone number, register number, or name written into app logs.
  - Device token, enrollment code, or encryption key exposed.
  - Bypassed PIN or authentication failure.
  - Vulnerability report submitted via `SECURITY.md` or private vulnerability reporting.
- **Mandatory Actions:**
  1. Open an **Incident Record** per doc 8 Section 12 with time zero ($T_0$).
  2. List immediate containment steps (target: within 4 hours), such as token revocation, secret rotation, rollout halt, or device de-enrollment.
  3. **Strictly No Public Issue:** Do not open a public GitHub issue. Store exclusively in private incident register.
  4. Stop and alert the technical lead at once.

### Bucket 3: Clear Defect
**Definition:** A verifiable, reproducible bug with steps or a stack trace that does not alter clinical triage decision-making or compromise privacy/security.
- Examples:
  - UI crashes or unhandled exceptions on non-clinical screens (e.g. Settings, Diagnostics).
  - Truncated text or formatting misalignment.
  - Camera preview aspect ratio glitch on specific device.
  - Research export CSV formatting bug.
  - Android Vitals crash or ANR cluster on a specific OS/device combination.
- **Mandatory Actions:**
  1. Assign severity `S1` to `S4` based on doc 9 Section 9.1:
     - `S1`: Core flow failure with data loss or security control break.
     - `S2`: Core flow failure with no workaround, or matrix phone failure.
     - `S3`: Non-core function fails or workaround exists.
     - `S4`: Cosmetic / styling discrepancy.
  2. File a defect using the template in `docs/11_Bug_Defect_Log.md` Section 7.
  3. Determine Code Tier from `quality/tiers.yaml`:
     - **Tier 2 (App) & Tier 3 (Tooling):** A fix branch and PR may be drafted in the same session.
     - **Tier 0 (Clinical) & Tier 1 (Data & Trust):** Write a reproduction plan and root-cause analysis only; do not generate an autonomous fix.

### Bucket 4: Confusion or User Request
**Definition:** The report indicates health worker confusion, workflow misunderstanding, unclear UI instructions, or a feature request.
- Examples:
  - "The app rejected the card saying lighting was uneven, but the room is bright."
  - "I am not sure how far to hold the phone from the baby's chest."
  - "Can we have a button to export to Excel?"
- **Mandatory Actions:**
  1. Mark status as **"needs a conversation"**.
  2. Formulate **2 to 3 clarifying questions** for the technical lead to ask the study site coordinator.
  3. Suggest a `/ng-fresh-eyes` task for the relevant screen and persona (e.g. Rokeya or Shirin) to inspect clarity and guidance.

### Bucket 5: Duplicate or Noise
**Definition:** Identical report to an existing open/closed defect, spam, or unsupported external noise.
- Examples:
  - Repeated crash cluster in Play Vitals already tracked under `BUG-2026-xxx`.
  - Duplicate issue filed by multiple users for the same known issue.
  - Test submissions or empty reports.
- **Mandatory Actions:**
  1. If duplicate: Link directly to the original defect ID (`BUG-YYYY-NNN`) and update affected device/version occurrence counts.
  2. If noise: Close with an explicit, polite reason explaining why no action was taken.

---

## 2. Reporter Response Protocols

- **Language:** Reply in the language of the reporter (Bangla or English). If unclear, default to bilingual or English.
- **No Date Commitments:** Never promise a specific release date or fix timeline to the reporter.
- **Tone:** Professional, appreciative, and clear.
- **Human Approval:** All drafts are placed in review; the human sends the actual response.

