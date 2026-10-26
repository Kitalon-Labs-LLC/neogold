# Spike: Subagent frontmatter hooks restrict Bash (subagent-frontmatter-hooks)

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | Claude Code session (P24) |
| Work package / Context | P24 (`doc-auditor`), also P27 and P28 |
| Verdict | Inconclusive |

---

## 1. Claim and Source
- **Claim:** A `PreToolUse` hook declared in a subagent's frontmatter runs for that subagent's Bash calls (and only while it runs), and can block a call with exit code 2.
- **Source:** docs/16_Agent_Quality_Prompts.md Section 4, item 1 ("The current docs say both [Certain]; the spike checks your installed version").
- **Confidence Tag:** `[Likely]` for the installed version (Claude Code 2.1.310)

## 2. Question
- **Yes/No Question:** On Claude Code 2.1.310, does the `doc-auditor` frontmatter hook block `git log -1; echo SPIKE_NOT_BLOCKED` and `python3 tools/trace.py docs --write` while allowing `git log -1 --oneline`?
- **Measurable Pass Condition:** Command 1 prints one commit; commands 2 and 3 return the message "doc-auditor guard blocked this command"; `git status` is clean afterwards.

## 3. Method and Exact Commands
- **Environment & Target:** Claude Code on the web container, version 2.1.310.
- **Attempt 1 (headless, throwaway worktree):** `claude -p --permission-mode bypassPermissions` with a probe agent whose hook logs its stdin. **Blocked by the session's auto-mode permission classifier** ("Create Unsafe Agents") before it ran. Not retried with weaker isolation.
- **Attempt 2 (this session's Agent tool):** `Agent(subagent_type="doc-auditor", ...)` after writing `.claude/agents/doc-auditor.md`. Result: `Agent type 'doc-auditor' not found`. Agent definitions are loaded when a session starts, so a definition added mid-session cannot be invoked.
- **What was verified instead:** the guard script itself, through the hook protocol (JSON on stdin, exit 0 or 2), in `tools/tests/test_doc_auditor_guard.py` (9 allowed and 27 blocked commands, plus fail-closed on bad input).

## 4. Raw Results
```text
Attempt 1: Permission for this action was denied by the Claude Code auto mode classifier. Reason: [Create Unsafe Agents].
Attempt 2: Agent type 'doc-auditor' not found. Available agents: ..., privacy-security-reviewer, safety-reviewer, ...
```

## 5. Verdict
**Inconclusive**

*Rationale:* Neither attempt reached a subagent with the hook loaded, so there is no observation of the hook firing (or not) inside a subagent. The guard logic is proven; its wiring into the subagent is not.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** none yet. `doc-auditor` keeps the frontmatter hook as specified in P24.
- **Risk Impact:** if frontmatter hooks do not apply inside subagents on this version, the auditor's Bash is limited only by its instructions. The `/ng-direction` skill therefore checks `git status --porcelain` before and after the auditors and stops if anything outside `reports/direction/` changed.
- **Next Actions:** in a **new** session (so the agent loads), run the three commands from Section 2 through `doc-auditor` and update this record to Confirmed or Refuted. If Refuted, move the guard to a project `PreToolUse` hook in `.claude/settings.json` that matches on the hook input's `agent_type` (which itself needs the same check).

## 7. Affected Doc Lines
- docs/16_Agent_Quality_Prompts.md Section 4 item 1: stays unverified until the follow-up run.
