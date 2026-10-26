# Spike: Subagent frontmatter hooks restrict Bash (subagent-frontmatter-hooks)

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | Claude Code session (P24) |
| Work package / Context | P24 (`doc-auditor`), also P27 and P28 |
| Verdict | Refuted (frontmatter hook did not run); `agent_type` in hook input Confirmed |

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
- **Attempt 3 (same session, after the harness announced `doc-auditor` as an available agent type):** `Agent(subagent_type="doc-auditor")` asked to run `git log -1 --oneline`, then `git log -1 --oneline; echo SPIKE_NOT_BLOCKED`, then `python3 tools/trace.py docs --write`.
- **Attempt 4:** added a project-level `PreToolUse` hook in `.claude/settings.json` that runs the same guard with `--project` (enforces only when the hook input's `agent_type` is `doc-auditor`), then repeated commands 1 and 2 through `doc-auditor`.
- **Also verified:** the guard script itself, through the hook protocol (JSON on stdin, exit 0 or 2), in `tools/tests/test_doc_auditor_guard.py` (9 allowed and 27 blocked commands, plus fail-closed on bad input).

## 4. Raw Results
```text
Attempt 1: Permission for this action was denied by the Claude Code auto mode classifier. Reason: [Create Unsafe Agents].
Attempt 2: Agent type 'doc-auditor' not found. Available agents: ..., privacy-security-reviewer, safety-reviewer, ...

Attempt 3, command 2 (frontmatter hook only):
82b9cda chore(direction): first drift inventory and trace counts for 2026-10-26 run (P24)
SPIKE_NOT_BLOCKED
(the subagent declined command 3 on its own instructions)

Same string fed to the guard directly:
$ echo '{"tool_name":"Bash","tool_input":{"command":"git log -1 --oneline; echo SPIKE_NOT_BLOCKED"}}' | python3 .claude/hooks/doc_auditor_bash_guard.py
doc-auditor guard blocked this command: shell operator ';' is not allowed; run one plain command   (exit 2)

Attempt 4, command 2 (project hook with agent_type check):
PreToolUse:Bash hook error: [python3 "$CLAUDE_PROJECT_DIR/.claude/hooks/doc_auditor_bash_guard.py" --project]: doc-auditor guard blocked this command: shell operator ';' is not allowed; run one plain command

Main session, same project hook: Bash commands with ';' run normally (no agent_type in its hook input).
```

## 5. Verdict
**Refuted** for the frontmatter hook; **Confirmed** that hook input carries `agent_type` inside a subagent.

*Rationale:* The guard blocks the chained command when called directly (exit 2), yet the chained command ran inside `doc-auditor`, so the frontmatter `PreToolUse` hook was not invoked. A project-level hook that checks `agent_type == "doc-auditor"` blocked the same command, which also shows `agent_type` is present. Caveat: the agent definition was added mid-session and picked up by a live reload; a fresh session may behave differently, but nothing here should depend on that.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** the enforcing registration is the project hook in `.claude/settings.json` (`--project`, keyed on `agent_type`). The frontmatter hook stays as specified in P24, as a second layer that costs nothing if it starts working.
- **Risk Impact:** P27 and P28 must not rely on frontmatter hooks for enforcement either; use the project hook with an `agent_type` check. `/ng-direction` still compares `git status --porcelain` before and after the auditors.
- **Next Actions:** re-run commands 1 and 2 through `doc-auditor` in a fresh session with the project hook removed, to learn whether the frontmatter path works when the agent is loaded at start-up.

## 7. Affected Doc Lines
- docs/16_Agent_Quality_Prompts.md Section 4 item 1: the `agent_type` half is confirmed by this record; the frontmatter half was refuted in this session and stays open for the fresh-session re-run.
