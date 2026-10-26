# Spike: [Short Title / Slug]

| Field | Value |
|---|---|
| Date | YYYY-MM-DD |
| Author | [Name / Role] |
| Work package / Context | [e.g. P23, P28] |
| Verdict | [Confirmed / Refuted / Inconclusive] |

---

## 1. Claim and Source
- **Claim:** [Exact statement of the claim being tested]
- **Source:** [Document and line number, vendor documentation URL, video citation, or model output]
- **Confidence Tag:** `[Likely]` / `[Guessing]`

## 2. Question
- **Yes/No Question:** [Clear, unambiguous yes-or-no question]
- **Measurable Pass Condition:** [Specific metric, numeric bound, exit code, or observable output that defines a pass/confirmation]

## 3. Method and Exact Commands
- **Environment & Target:** [Target ABI (arm64-v8a, armeabi-v7a), Emulator API level (API 26, API 36), physical matrix device, or local isolated worktree]
- **Isolation:** Throwaway worktree `../neogold-spike-<slug>` or subagent with `isolation: worktree`.
- **Exact Commands Run:**
  ```bash
  # Step-by-step reproducible commands
  ```

## 4. Raw Results
- **Console / Tool Output:**
  ```text
  [Paste raw execution output, logs, or benchmark output here]
  ```
- **Observations:** [Key facts observed during execution]

## 5. Verdict
**[Confirmed | Refuted | Inconclusive]**

*Rationale:* [Explanation of how the raw results meet or fail the measurable pass condition. "Seems to work" without reproducible evidence is strictly Inconclusive.]

## 6. What the Verdict Changes
- **Architectural / Code Impact:** [Does code need to change, or is the existing assumption validated?]
- **Risk Impact:** [Clinical safety, privacy, security, cost, or schedule change]
- **Next Actions:** [Any follow-up tasks, issues opened, or tests added]

## 7. Affected Doc Lines
- [Document and line number]: Update `[Likely]` or `[Guessing]` to `[Certain]` citing this spike record, or apply necessary correction.
