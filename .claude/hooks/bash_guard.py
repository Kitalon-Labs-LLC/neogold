#!/usr/bin/env python3
"""
PreToolUse hook: bash_guard.py

Enforces P25 / Doc 8 Section 4, 10 / Doc 12 Section 2:
Denies:
- --no-verify
- git push --force (or -f)
- commits on main
- -x test or -x check in Gradle calls
- any command that reads a keystore or a .env file
"""

import json
import re
import shlex
import subprocess
import sys
from pathlib import Path

READ_COMMANDS = {"cat", "head", "tail", "less", "more", "grep", "source", "type", "open", "xxd", "hexdump"}


def get_current_git_branch() -> str:
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        return proc.stdout.strip()
    except Exception:
        return ""


def verdict(command: str) -> str | None:
    if not command or not command.strip():
        return None

    # 1. Deny --no-verify
    if "--no-verify" in command:
        return "Bypassing git hooks with --no-verify is strictly forbidden (P25)."

    # 2. Deny git push --force / -f
    if re.search(r"\bgit\s+push\b.*(-f\b|--force\b|--force-with-lease\b)", command):
        return "Force pushing with git is strictly forbidden (P25)."

    # 3. Deny commits on main
    if re.search(r"\bgit\s+commit\b", command):
        branch = get_current_git_branch()
        if branch in ("main", "master"):
            return f"Committing directly to '{branch}' is forbidden; work must be done on a branch cut from dev (Doc 12 Section 2)."

    # 4. Deny -x test or -x check in Gradle calls
    if re.search(r"(gradle|gradlew)\b", command):
        if re.search(r"(-x\s+(test|check|lintRelease)|--exclude-task\s+(test|check|lintRelease))", command):
            return "Skipping test or check tasks in Gradle calls is strictly forbidden (P25)."

    # 5. Deny any command that reads a keystore or a .env file
    # Check for reading .env files
    if re.search(r"\b(cat|head|tail|less|more|grep|source|type|bat|sed|awk|xxd|hexdump)\b.*(\.env\b|\.env\.)", command) or re.search(r"<+\s*[^;\s|&]*\.env\b", command):
        return "Directly reading .env files is strictly forbidden to protect secrets (Doc 8 Section 4)."

    # Check for reading keystores (.jks, .keystore)
    if re.search(r"\.(jks|keystore)\b", command):
        if not re.search(r"\b(check_16kb|bundletool)\b", command):
            return "Accessing or reading keystore files is strictly forbidden (Doc 8 Section 4)."

    return None


def main() -> int:
    try:
        data = json.load(sys.stdin)
        if not isinstance(data, dict):
            raise ValueError("hook input is not an object")
    except Exception as e:
        print(f"bash_guard: unreadable hook input ({e})", file=sys.stderr)
        return 2

    tool_input = data.get("tool_input")
    if not isinstance(tool_input, dict):
        print("bash_guard: missing tool_input", file=sys.stderr)
        return 2

    command = tool_input.get("command", "")
    if not isinstance(command, str):
        print("bash_guard: command must be a string", file=sys.stderr)
        return 2

    reason = verdict(command)
    if reason:
        out = {
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            }
        }
        print(json.dumps(out))
        print(f"bash_guard denied: {reason}", file=sys.stderr)
        return 2

    return 0


if __name__ == "__main__":
    sys.exit(main())
