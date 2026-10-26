---
name: ng-spike
description: Test one unverified claim from scratch in a throwaway worktree and record the evidence, so no decision rests on "the docs say so" or model output.
disable-model-invocation: true
argument-hint: "\"<claim>\""
---

# Spike Execution Protocol

Execute an isolated, reproducible technical investigation to verify or refute an unproven claim (`[Likely]` or `[Guessing]`).

Spike code lives only in a throwaway worktree and **never** merges into production branches (`android/`, `backend/`, `training/`, or `config/`).

## Procedure

### Step 1: Clarify Question and Condition
1. Restate the claim as an unambiguous yes-or-no question.
2. Formulate an objective, measurable pass condition (e.g. exit code 0, timing under X ms, exact memory consumption, bit-identical output).
3. Identify the origin of the claim: document and line number, vendor specification URL, or video/model citation.
4. **Constraint:** If there is no objective, measurable pass condition, stop and ask the user before proceeding.

### Step 2: Establish Worktree Isolation
Create a throwaway git worktree adjacent to the repository, branching from `main` (or run inside a subagent configured with `isolation: worktree`):
```bash
SLUG="<slug-from-claim>"
WORKTREE_PATH="../neogold-spike-$SLUG"
git worktree add "$WORKTREE_PATH" main
cd "$WORKTREE_PATH"
```
*Note: Spike code, scratch experiments, and intermediate benchmarks must be isolated strictly inside this worktree.*

### Step 3: Implement and Execute Minimal Experiment
1. Build the absolute minimum proof artifact (script, test harness, or micro-benchmark) required to answer the question.
2. Never import unapproved dependencies or libraries without prior approval.
3. Never use real patient or newborn data; use synthetic, phantom, or bench inputs.
4. Run the proof on the target where it matters:
   - Both target ABIs (`arm64-v8a` and `armeabi-v7a`).
   - Android emulators (API 26 floor and API 36 target).
   - Reference physical matrix devices or host environment if applicable.
5. Capture exact shell commands and unedited raw stdout/stderr output.

### Step 4: Record Results
1. Create the spike report file in the main repository: `docs/spikes/YYYY-MM-DD-<slug>.md` following `docs/spikes/TEMPLATE.md`.
2. Fill in all required sections:
   - Claim and source citation
   - Question and measurable pass condition
   - Method and exact commands
   - Raw results and logs
   - Verdict: **Confirmed**, **Refuted**, or **Inconclusive** ("seems to work" without reproducible logs is strictly Inconclusive)
   - What the verdict changes (architecture, risk, next steps)
   - Affected document lines

### Step 5: Clean Up Worktree and Commit Record
1. Remove the throwaway worktree:
   ```bash
   cd ../neogold-android
   git worktree remove "$WORKTREE_PATH" --force
   ```
2. Create/checkout a dedicated branch `spike/<slug>`:
   ```bash
   git checkout -b "spike/$SLUG"
   ```
3. Commit **only** the spike record `docs/spikes/YYYY-MM-DD-<slug>.md` and the update to the corresponding document:
   - If **Confirmed**: update the tag in the design doc to `[Certain]` with a link to the spike record.
   - If **Refuted**: update the document with the required correction and note the spike record.
   - If **Inconclusive**: leave the tag unverified and document the required next steps.
4. Open a pull request into `dev` detailing the findings.
