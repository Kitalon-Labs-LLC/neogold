#!/usr/bin/env python3
"""
PreToolUse hook: protect_clinical.py

Enforces P25 / PRD D11, 9b:
Denies edits to config/triage-config.json, CLINICAL_SIGNOFF.md, the trust list,
released model files, and the clinical strings.

The denial message is:
"Clinical values change only with the clinical lead's signed record (PRD 9b)."
"""

import json
import os
import sys
from pathlib import Path

DENIAL_MESSAGE = "Clinical values change only with the clinical lead's signed record (PRD 9b)."

PROTECTED_EXACT = {
    "config/triage-config.json",
    "clinical_signoff.md",
    "config/trust-list.json",
    "config/model.json",
    "android/app/src/main/assets/model.json",
    "android/core/designsystem/src/main/res/values/strings.xml",
    "android/core/designsystem/src/main/res/values-bn/strings.xml",
}


def normalize_rel_path(path_str: str) -> str:
    path_str = path_str.replace("\\", "/").strip().lower()
    # Strip any leading slashes or ./
    while path_str.startswith("./"):
        path_str = path_str[2:]
    return path_str


def is_protected(path_str: str) -> bool:
    norm = normalize_rel_path(path_str)
    # Check exact match or relative endswith
    for p in PROTECTED_EXACT:
        if norm == p or norm.endswith("/" + p):
            return True
    return False


def verdict(file_path: str) -> str | None:
    if not file_path:
        return "empty file path"
    if is_protected(file_path):
        return DENIAL_MESSAGE
    return None


def main() -> int:
    try:
        data = json.load(sys.stdin)
        if not isinstance(data, dict):
            raise ValueError("hook input is not an object")
    except Exception as e:
        print(f"protect_clinical: unreadable hook input ({e})", file=sys.stderr)
        return 2

    tool_input = data.get("tool_input")
    if not isinstance(tool_input, dict):
        print("protect_clinical: missing or invalid tool_input", file=sys.stderr)
        return 2

    # Extract target file path (Edit, Write, NotebookEdit)
    file_path = tool_input.get("file_path") or tool_input.get("path") or tool_input.get("notebook_path") or ""
    if not isinstance(file_path, str):
        print("protect_clinical: file_path must be a string", file=sys.stderr)
        return 2

    reason = verdict(file_path)
    if reason:
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

    return 0


if __name__ == "__main__":
    sys.exit(main())
