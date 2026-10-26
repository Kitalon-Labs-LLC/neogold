---
name: ng-assumptions
description: Audit unverified claims and confidence tags across design documents, rank them by potential impact if wrong, and output ready-to-run /ng-spike commands.
disable-model-invocation: true
---

# Assumption Audit Protocol

Identify and catalog all unproven claims (`[Likely]` and `[Guessing]`) embedded in design documents (`docs/2_PRD.md` to `docs/16_Agent_Quality_Prompts.md`).

Determine code dependencies, existing test proofs, and categorize failure impact:
- **Clinical:** Direct risk to newborn safety or triage accuracy.
- **Privacy:** Exposure or retention of sensitive health or identifier data.
- **Security:** Compromise of cryptographic keys, attestation, or server boundaries.
- **Cost:** Financial overhead in hardware, cloud hosting, or licensing.
- **Schedule:** Blockers to milestone verification or study enrollment.

## Procedure

### Step 1: Execute Assumptions Inventory
Run the automated assumptions scanner:
```bash
python tools/assumptions.py --report
```
The script scans design docs, maps citations to code and tests, ranks items by impact priority, includes the candidate spikes from `docs/16_Agent_Quality_Prompts.md` Section 4, and writes the output report to:
`reports/assumptions/YYYY-MM-DD.md`

### Step 2: Review and Verify Audit Table
For each identified tag:
1. **Code dependency:** Check whether production code depends on this assumption; cite the exact file and line number.
2. **Test proof:** Check whether an existing automated test proves the claim conclusively; cite the `TC-` ID or test method. If only synthetic or mock tests exist, mark as unproven.
3. **Impact rating:** Assess severity (Clinical > Privacy > Security > Cost > Schedule).

### Step 3: Inspect Top 10 Priority Claims
The report highlights the top 10 unproven claims as ready-to-run `/ng-spike` command invocations:
```bash
/ng-spike "<claim>"
```

### Step 4: Commit and Track
Commit the generated report:
```bash
git add reports/assumptions/YYYY-MM-DD.md
git commit -m "docs(assumptions): update unverified claims audit for YYYY-MM-DD"
```
Re-run this audit monthly, before each milestone release candidate (M1, M2, M3), and whenever significant architectural changes are proposed.
