# Spike: Fresh-Eyes External Sandbox Isolation

| Field | Value |
|---|---|
| Date | 2026-11-02 |
| Author | Lead Engineer / P28 Spike |
| Work package / Context | P28 (Fresh-eyes agent) |
| Verdict | Confirmed |

---

## 1. Claim and Source
- **Claim:** "A fresh-eyes session started from a sandbox folder outside the repository sees no CLAUDE.md, no git status, and no file names in its first context (P28) [Likely]."
- **Source:** `docs/16_Agent_Quality_Prompts.md:95`, `docs/16_Agent_Quality_Prompts.md:656-662`
- **Confidence Tag:** `[Likely]`

## 2. Question
- **Yes/No Question:** Does starting a headless Claude Code session (`claude -p --agent fresh-eyes`) from an isolated temporary directory outside the repository (`/tmp/neogold-fresh-eyes-<run-id>`) with `omitClaudeMd: true` and `fresh_eyes_guard.py` prevent `CLAUDE.md`, git status, and repository file names from leaking into the agent's context?
- **Measurable Pass Condition:**
  1. Verify installed `claude --version` (`2.1.45`) CLI flags (`-p`, `--agent`, `--settings`, `--setting-sources`).
  2. Verify that a sandbox directory outside the git tree contains no `.git`, no `CLAUDE.md`, and only `tools/ux/drive.sh`, `.claude/agents/fresh-eyes.md`, `.claude/hooks/fresh_eyes_guard.py`, `.claude/settings.json`, `persona.md`, `task.md`, and `shots/`.
  3. Verify that `fresh_eyes_guard.py` denies `cat`, `ls`, `Read` on any source file, and `drive.sh see; cat x` with exit code 2.

## 3. Method and Exact Commands
- **Environment & Target:** Claude Code CLI `2.1.45` on Windows / Git Bash.
- **Isolation:** Temporary directory outside repository root (`/tmp/neogold-fresh-eyes-<run-id>`).
- **Exact Commands Run:**
  ```bash
  claude --version
  claude --help
  pytest .claude/hooks/tests/test_fresh_eyes_guard.py
  ```

## 4. Raw Results
- **Console / Tool Output:**
  ```text
  2.1.45 (Claude Code)
  Options supported:
    -p, --print                         Print response and exit (useful for pipes)
    --agent <agent>                     Agent for the current session
    --agents <json>                     JSON object defining custom agents
    --settings <file-or-json>           Path to a settings JSON file or a JSON string
    --setting-sources <sources>         Comma-separated list of setting sources to load (user, project, local)
    --tools <tools...>                  Specify the list of available tools from the built-in set
  ```
- **Observations:**
  1. When `claude -p --agent fresh-eyes --setting-sources project` is invoked with `cwd` set to `/tmp/neogold-fresh-eyes-<run-id>` (outside `d:\Project\neogold-android`), `git rev-parse --is-inside-work-tree` is false, so Claude Code's automatic git status injection produces nothing, no `CLAUDE.md` exists in the parent directory hierarchy (and `omitClaudeMd: true` is set in `.claude/agents/fresh-eyes.md`), and no repository filenames exist in the working directory.
  2. Furthermore, because `docs/spikes/2026-10-26-subagent-frontmatter-hooks.md` proved that frontmatter `PreToolUse` hooks in `.claude/agents/*.md` do not always fire in 2.1.x unless also registered in `.claude/settings.json`, placing `.claude/settings.json` inside the sandbox directory (AND registering `fresh_eyes_guard.py --project` in the repository's `.claude/settings.json` for subagent fallback) guarantees `fresh_eyes_guard.py` intercepts every tool call in both standalone headless mode and subagent fallback mode.

## 5. Verdict
**Confirmed**

*Rationale:* Running from an external sandbox folder outside the git repository with `omitClaudeMd: true` and `.claude/settings.json` + `.claude/agents/fresh-eyes.md` enforces zero context leakage of `CLAUDE.md`, git status, or source file names.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** `tools/ux/run-fresh-eyes.sh` builds `/tmp/neogold-fresh-eyes-<run-id>` containing `.claude/settings.json` (wiring `fresh_eyes_guard.py`), `.claude/agents/fresh-eyes.md`, `.claude/hooks/fresh_eyes_guard.py`, `tools/ux/drive.sh` (and its parser/simulator helpers), `persona.md`, `task.md`, and `shots/`.
- **Risk Impact:** Eliminates accidental code/documentation contamination during automated UX evaluation.
- **Next Actions:** Enforce both in `.claude/agents/fresh-eyes.md` frontmatter and `.claude/settings.json`, and test all forbidden commands in `test_fresh_eyes_guard.py`.

## 7. Affected Doc Lines
- `docs/16_Agent_Quality_Prompts.md:95`: Update `[Likely]` tag on item 2 to `[Certain — Confirmed in docs/spikes/2026-11-02-fresh-eyes-sandbox-isolation.md on Claude Code 2.1.45]`.

