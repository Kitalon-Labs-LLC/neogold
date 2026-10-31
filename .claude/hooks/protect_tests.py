#!/usr/bin/env python3
"""
PreToolUse hook: protect_tests.py

Enforces P25 / Doc 9 Section 7:
1. Denies an edit that adds a skip marker to a Tier 0 or Tier 1 test file.
2. Returns `ask` for a skip marker in Tier 2 or Tier 3 (human approval for quarantining flaky test).
3. Denies a Bash `rm`, `git rm`, or `mv` of a test file in Tier 0 or Tier 1.
"""

import json
import re
import shlex
import sys
from pathlib import Path

SKIP_PATTERNS = [
    re.compile(r"@Ignore\b"),
    re.compile(r"@Disabled\b"),
    re.compile(r"assumeTrue\s*\(\s*false\s*\)"),
    re.compile(r"assumeFalse\s*\(\s*true\s*\)"),
    re.compile(r"@?pytest\.mark\.skip\b"),
    re.compile(r"@?pytest\.mark\.xfail\b"),
    re.compile(r"\bpytest\.skip\s*\("),
    re.compile(r"\bpytest\.xfail\s*\("),
]

TIER0_AND_1_PREFIXES = (
    # Tier 0 (Clinical)
    "android/engine/triage/src/test",
    "android/engine/estimation/src/test",
    "android/engine/colorscience/src/test",
    "android/engine/cardtrust/src/test",
    "android/engine/vision/src/test",
    "android/feature/result/src/test",
    # Tier 1 (Data & Trust)
    "android/core/security/src/test",
    "android/core/data/src/test",
    "android/sync/research/src/test",
    "tools/neogold-decrypt/src/test",
    "backend/tests",
)


def normalize_rel_path(path_str: str) -> str:
    path_str = path_str.replace("\\", "/").strip().lower()
    while path_str.startswith("./"):
        path_str = path_str[2:]
    return path_str


def is_tier0_or_tier1_test(path_str: str) -> bool:
    norm = normalize_rel_path(path_str)
    for prefix in TIER0_AND_1_PREFIXES:
        if norm.startswith(prefix) or ("/" + prefix) in norm:
            return True
    return False


def is_test_file(path_str: str) -> bool:
    norm = normalize_rel_path(path_str)
    return "test" in norm or "tests" in norm


def content_has_skip_marker(content: str) -> str | None:
    for pat in SKIP_PATTERNS:
        m = pat.search(content)
        if m:
            return m.group(0)
    return None


def evaluate_edit_or_write(file_path: str, content: str) -> tuple[str, str | None]:
    """Return (decision, reason) where decision is 'allow', 'deny', or 'ask'."""
    if not is_test_file(file_path):
        return "allow", None

    marker = content_has_skip_marker(content)
    if marker:
        if is_tier0_or_tier1_test(file_path):
            return "deny", f"Skipping tests in Tier 0/1 test files is strictly forbidden: found '{marker}' (Doc 9 Section 7)."
        else:
            return "ask", f"Adding skip marker '{marker}' in Tier 2/3 test requires human confirmation with a linked defect (Doc 9 Section 7)."

    return "allow", None


def evaluate_bash_command(command: str) -> tuple[str, str | None]:
    """Check if command removes or moves Tier 0 or Tier 1 test files."""
    try:
        tokens = shlex.split(command)
    except Exception:
        tokens = command.split()

    if not tokens:
        return "allow", None

    is_destructive = False
    if tokens[0] in ("rm", "mv"):
        is_destructive = True
    elif len(tokens) >= 2 and tokens[0] == "git" and tokens[1] in ("rm", "mv"):
        is_destructive = True

    if is_destructive:
        for arg in tokens:
            if is_tier0_or_tier1_test(arg):
                return "deny", f"Deleting or moving Tier 0/1 test file '{arg}' is strictly forbidden (Doc 9 Section 7)."

    return "allow", None


def main() -> int:
    try:
        data = json.load(sys.stdin)
        if not isinstance(data, dict):
            raise ValueError("hook input is not an object")
    except Exception as e:
        print(f"protect_tests: unreadable hook input ({e})", file=sys.stderr)
        return 2

    tool_name = data.get("tool_name", "")
    tool_input = data.get("tool_input")
    if not isinstance(tool_input, dict):
        print("protect_tests: missing tool_input", file=sys.stderr)
        return 2

    decision = "allow"
    reason = None

    if tool_name in ("Edit", "Write", "NotebookEdit"):
        file_path = tool_input.get("file_path") or tool_input.get("path") or tool_input.get("notebook_path") or ""
        # Check text in new_string, replacement, content
        content = (
            tool_input.get("content")
            or tool_input.get("new_string")
            or tool_input.get("replacement")
            or ""
        )
        decision, reason = evaluate_edit_or_write(file_path, content)

    elif tool_name == "Bash":
        command = tool_input.get("command", "")
        decision, reason = evaluate_bash_command(command)

    if decision == "deny":
        out = {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            }
        }
        print(json.dumps(out))
        print(reason, file=sys.stderr)
        return 2

    elif decision == "ask":
        out = {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "ask",
                "permissionDecisionReason": reason,
            }
        }
        print(json.dumps(out))
        return 0

    return 0


if __name__ == "__main__":
    sys.exit(main())
