# NeoGold Spikes

Spikes are timeboxed, experimental investigations designed to test specific technical, clinical, architectural, or regulatory assumptions before committing to or relying upon them.

The goal of a spike is evidence: replacing "the docs say so," "a video said so," or model inferences (`[Likely]`, `[Guessing]`) with reproducible, measurable verification.

---

## The Non-Negotiable Rule: Spike Code Never Merges

> [!CAUTION]
> **Spike code NEVER merges into production branches.**
> A spike never modifies `android/`, `backend/`, `training/`, or `config/` on any branch intended for merge into `dev` or `main`.

1. **Strict Isolation:** Every spike runs either in a throwaway git worktree (`../neogold-spike-<slug>`) cut from an up-to-date base commit, or within an isolated subagent (`isolation: worktree`).
2. **Disposable Work:** Once the question is answered and raw results are collected, the worktree is completely deleted.
3. **Commit the Record Only:** Only the spike markdown report (`docs/spikes/YYYY-MM-DD-<slug>.md`) and the corresponding doc tag update (`[Certain]` or doc correction) are committed to a branch `spike/<slug>` and submitted for pull request review.
4. **Third-Party Dependencies:** A spike that requires a new tool, binary, or external library must ask for approval first, just like any production dependency.
5. **No Real Patient Data:** Real newborn data or clinical patient imagery must **never** enter a spike workspace or spike log. Always use synthetic, phantom, or bench datasets.

---

## Spike Lifecycle

1. **State the Claim & Question:**
   - Define a binary yes-or-no question with an objective, measurable pass condition.
   - Trace the claim back to its primary source (doc and line, vendor specification, empirical observation).
   - If no measurable condition exists, stop and clarify requirements before writing code.

2. **Create Throwaway Worktree:**
   ```bash
   git worktree add ../neogold-spike-<slug> main
   cd ../neogold-spike-<slug>
   ```

3. **Build Minimal Proving Artifact:**
   - Write the smallest possible test or script that conclusively resolves the question.
   - Run on relevant targets: both ABIs (`arm64-v8a`, `armeabi-v7a`), Android API levels (API 26 floor and API 36 target emulators), or reference matrix devices.

4. **Document Results:**
   - Fill in `docs/spikes/YYYY-MM-DD-<slug>.md` using [TEMPLATE.md](TEMPLATE.md).
   - Record exact commands run and paste unaltered raw outputs.
   - Assign a verdict:
     - **Confirmed:** Pass criteria met with reproducible logs.
     - **Refuted:** Condition failed; assumption is disproven.
     - **Inconclusive:** Evidence was ambiguous, environmental, or unrepeatable. ("Seems to work" is strictly Inconclusive.)

5. **Clean Up & Update Docs:**
   ```bash
   cd ../neogold-android
   git worktree remove ../neogold-spike-<slug> --force
   ```
   - On branch `spike/<slug>`, commit the spike record in `docs/spikes/` and update the relevant doc line (e.g., replace `[Likely]` or `[Guessing]` with `[Certain]` pointing to the spike file, or correct the design).

---

## Commands

- Run assumption inventory: `/ng-assumptions`
- Execute a spike: `/ng-spike "<claim>"`
