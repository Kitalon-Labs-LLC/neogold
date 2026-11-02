#!/usr/bin/env python3
"""PreToolUse guard for the `fresh-eyes` agent (P28).

Enforces P28 Build 3 & Constraints:
- Only two actions are allowed, and everything else is denied:
  1. `Bash`, when the command is `tools/ux/drive.sh` with a valid subcommand and no shell operators.
  2. `Read`, for `.png` files inside the current run's `shots/` folder.
- Specifically blocks `cat`, `ls`, `Read` on source/doc/config files, `drive.sh see; cat x`,
  and any other tool (`Write`, `Edit`, `Glob`, `Grep`, etc.).

Supports both:
- Direct registration in `.claude/agents/fresh-eyes.md` frontmatter and sandbox `.claude/settings.json`
  (always enforces on every tool call).
- Project-level registration in repo `.claude/settings.json` via `--project`
  (enforces when `agent_type == "fresh-eyes"`, per `docs/spikes/2026-10-26-subagent-frontmatter-hooks.md`).
"""

from __future__ import annotations

import json
import os
import re
import shlex
import sys
from pathlib import Path

# Characters that could chain commands, redirect I/O, or invoke subshells in Bash
SHELL_OPERATOR_CHARS = set(";|&`$><\n\r(){}\\")

VALID_DRIVE_SCRIPTS = {"tools/ux/drive.sh", "./tools/ux/drive.sh"}
VALID_SUBCOMMANDS = {
    "shot",
    "see",
    "tap",
    "tap-text",
    "type",
    "swipe",
    "back",
    "home",
    "wait",
    "launch",
}

SHOT_NAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
COORD_RE = re.compile(r"^\d{1,5}$")
WAIT_RE = re.compile(r"^\d{1,2}$")
TYPE_TEXT_RE = re.compile(r"^[A-Za-z0-9 ./_-]{1,64}$")
PNG_FILENAME_RE = re.compile(r"^[A-Za-z0-9._-]+\.png$", re.IGNORECASE)

FORBIDDEN_PATH_SEGMENTS = {
    "android",
    "backend",
    "docs",
    "breaker",
    "quality",
    ".claude",
    ".git",
    ".github",
}


def evaluate_bash(command: str) -> str | None:
    """Return None if allowed, or a denial reason string."""
    if not command or not command.strip():
        return "Empty Bash command is not allowed; only tools/ux/drive.sh is permitted."

    # 1. Reject any shell operators, newlines, subshells, or redirections
    bad_chars = [ch for ch in command if ch in SHELL_OPERATOR_CHARS]
    if bad_chars:
        unique_bad = "".join(sorted(set(bad_chars)))
        return (
            f"Shell operators or control characters ({unique_bad!r}) are forbidden. "
            f"Only a single direct call to tools/ux/drive.sh is permitted."
        )

    # 2. Parse tokens without shell evaluation
    try:
        tokens = shlex.split(command, posix=True)
    except ValueError as exc:
        return f"Malformed command quoting ({exc}); only tools/ux/drive.sh is permitted."

    if not tokens:
        return "Empty command tokens."

    # Allow optional leading `bash` binary only if followed immediately by tools/ux/drive.sh
    if tokens[0] == "bash":
        tokens = tokens[1:]

    if not tokens or tokens[0] not in VALID_DRIVE_SCRIPTS:
        return (
            f"Command {tokens[0] if tokens else command!r} is forbidden. "
            f"fresh-eyes may only run 'tools/ux/drive.sh'."
        )

    if len(tokens) < 2:
        return "Missing subcommand for tools/ux/drive.sh."

    sub = tokens[1]
    args = tokens[2:]

    if sub not in VALID_SUBCOMMANDS:
        return f"Unknown drive.sh subcommand {sub!r}."

    if sub == "shot":
        if len(args) != 1 or not SHOT_NAME_RE.match(args[0]):
            return "drive.sh shot requires exactly 1 alphanumeric/slug name."
    elif sub in ("see", "back", "home", "launch"):
        if len(args) != 0:
            return f"drive.sh {sub} takes no arguments."
    elif sub == "tap":
        if len(args) != 2 or not (COORD_RE.match(args[0]) and COORD_RE.match(args[1])):
            return "drive.sh tap requires <x> <y> integer coordinates."
    elif sub == "tap-text":
        if len(args) != 1 or not args[0].strip() or len(args[0]) > 120:
            return 'drive.sh tap-text requires a single non-empty "<label>" argument.'
    elif sub == "type":
        if len(args) != 1 or not TYPE_TEXT_RE.match(args[0]):
            return 'drive.sh type requires digits/Latin letters only ("<text>").'
    elif sub == "swipe":
        if len(args) != 1 or args[0] not in {"up", "down", "left", "right"}:
            return "drive.sh swipe requires one of: up, down, left, right."
    elif sub == "wait":
        if len(args) != 1 or not WAIT_RE.match(args[0]):
            return "drive.sh wait requires integer seconds (0-99)."

    return None


def evaluate_read(file_path: str) -> str | None:
    """Return None if file_path is a PNG inside the current run's shots/ folder, else denial reason."""
    if not file_path or not file_path.strip():
        return "Read requires a valid file_path inside shots/."

    norm = file_path.replace("\\", "/").strip()
    while norm.startswith("./"):
        norm = norm[2:]

    parts = [p for p in norm.split("/") if p]
    if not parts:
        return "Invalid file_path."

    if ".." in parts:
        return f"Path traversal ('..') is forbidden in Read ({file_path!r})."

    lower_parts = [p.lower() for p in parts]

    # Never allow reading anything inside repo source/doc/config folders or CLAUDE.md
    if any(seg in FORBIDDEN_PATH_SEGMENTS for seg in lower_parts):
        return (
            f"Reading repository source, documentation, or config files ({file_path!r}) is "
            f"strictly forbidden for fresh-eyes."
        )

    filename = parts[-1]
    if filename.lower() == "claude.md" or not PNG_FILENAME_RE.match(filename):
        return (
            f"fresh-eyes may only Read PNG screenshot files (*.png) inside shots/ "
            f"(got {file_path!r})."
        )

    # Must reside directly inside a `shots` folder (`shots/<name>.png` or `<run-dir>/shots/<name>.png`)
    if len(parts) < 2 or parts[-2] != "shots":
        return (
            f"Read path {file_path!r} must be inside the current run's 'shots/' folder "
            f"(e.g., 'shots/{filename}')."
        )

    # If FRESH_EYES_SHOTS_DIR is set in the environment, enforce that resolved path stays within it
    shots_dir_env = os.environ.get("FRESH_EYES_SHOTS_DIR", "").strip()
    if shots_dir_env:
        try:
            allowed_dir = Path(shots_dir_env).resolve()
            candidate = Path(file_path)
            if not candidate.is_absolute():
                # Could be `shots/foo.png` relative to sandbox root (`allowed_dir.parent`)
                candidate = (allowed_dir.parent / candidate).resolve()
            else:
                candidate = candidate.resolve()
            if candidate.parent != allowed_dir:
                return (
                    f"Read path {file_path!r} is outside the active run's shots/ directory "
                    f"({allowed_dir})."
                )
        except OSError:
            return f"Invalid Read path {file_path!r}."

    return None


def deny_response(reason: str) -> int:
    out = {
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": f"fresh_eyes_guard: {reason}",
        }
    }
    print(json.dumps(out))
    print(f"fresh_eyes_guard denied: {reason}", file=sys.stderr)
    return 2


def main() -> int:
    project_mode = "--project" in sys.argv[1:]
    try:
        raw = sys.stdin.read()
        if not raw.strip():
            return 0
        payload = json.loads(raw)
    except Exception:
        return 0

    if project_mode:
        agent_type = str(payload.get("agent_type") or "")
        if agent_type != "fresh-eyes":
            return 0

    tool_name = str(payload.get("tool_name") or "")
    tool_input = payload.get("tool_input") or {}

    if tool_name == "Bash":
        command = str(tool_input.get("command") or "")
        reason = evaluate_bash(command)
        if reason:
            return deny_response(reason)
        return 0

    if tool_name == "Read":
        file_path = str(tool_input.get("file_path") or "")
        reason = evaluate_read(file_path)
        if reason:
            return deny_response(reason)
        return 0

    return deny_response(
        f"Tool {tool_name!r} is forbidden for fresh-eyes; only Bash (tools/ux/drive.sh) "
        f"and Read (shots/*.png) are allowed."
    )


if __name__ == "__main__":
    sys.exit(main())

