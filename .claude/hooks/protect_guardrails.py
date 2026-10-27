#!/usr/bin/env python3
"""
PreToolUse hook: protect_guardrails.py

Enforces P25:
Returns `ask`, not `deny`, so the human confirms each edit to any of these:
- .claude/settings.json
- .claude/hooks/ and .claude/agents/
- quality/
- tools/check_*
- .github/workflows/
"""

import json
import sys
from pathlib import Path

GUARDRAIL_PATTERNS = [
    ".claude/settings.json",
    ".claude/hooks/",
    ".claude/agents/",
    "quality/",
    "tools/check_",
    ".github/workflows/",
]


def normalize_rel_path(path_str: str) -> str:
    path_str = path_str.replace("\\", "/").strip().lower()
    while path_str.startswith("./"):
        path_str = path_str[2:]
    return path_str


def is_guardrail_path(path_str: str) -> bool:
    norm = normalize_rel_path(path_str)
    for pattern in GUARDRAIL_PATTERNS:
        if pattern in norm:
            return True
    return False


def verdict(file_path: str) -> str | None:
    if is_guardrail_path(file_path):
        return f"Editing guardrail file '{file_path}' requires human confirmation."
    return None


def main() -> int:
    try:
        data = json.load(sys.stdin)
        if not isinstance(data, dict):
            raise ValueError("hook input is not an object")
    except Exception as e:
        print(f"protect_guardrails: unreadable hook input ({e})", file=sys.stderr)
        return 2

    tool_input = data.get("tool_input")
    if not isinstance(tool_input, dict):
        print("protect_guardrails: missing tool_input", file=sys.stderr)
        return 2

    file_path = tool_input.get("file_path") or tool_input.get("path") or tool_input.get("notebook_path") or ""
    if not isinstance(file_path, str):
        print("protect_guardrails: file_path must be a string", file=sys.stderr)
        return 2

    ask_reason = verdict(file_path)
    if ask_reason:
        out = {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "ask",
                "permissionDecisionReason": ask_reason,
            }
        }
        print(json.dumps(out))
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
