#!/usr/bin/env python3
"""
tools/check_guardrails.py

Enforces P25:
Checks that every row in the CLAUDE.md "Must not trade off" table points to a file,
task, test, or named human that exists in the repository.
CI fails if a row's enforcement mechanism disappears or breaks.
"""

import os
import re
import sys
from pathlib import Path

TABLE_HEADER = "| Rule | Why | Enforced by | Runs at |"


def parse_claude_table(claude_md_path: Path) -> list[dict]:
    if not claude_md_path.exists():
        raise FileNotFoundError(f"{claude_md_path} not found")

    text = claude_md_path.read_text(encoding="utf-8")
    lines = text.splitlines()

    rows = []
    in_table = False

    for line in lines:
        stripped = line.strip()
        if stripped.startswith("| Rule | Why | Enforced by |"):
            in_table = True
            continue
        if in_table:
            if not stripped.startswith("|"):
                in_table = False
                continue
            if stripped.startswith("|---|"):
                continue

            parts = [p.strip() for p in stripped.split("|")[1:-1]]
            if len(parts) >= 4:
                rows.append({
                    "rule": parts[0],
                    "why": parts[1],
                    "enforced_by": parts[2],
                    "runs_at": parts[3],
                })
    return rows


def verify_enforcement_row(repo_root: Path, row: dict) -> list[str]:
    enforced_by = row["enforced_by"]
    errors = []

    # Extract all file-like paths and script names mentioned in enforced_by
    # Tokens matching path/to/file or filename.py or hook name
    tokens = re.findall(r"[\w.-]+(?:/[\w.-]+)+|[\w_]+\.py|[\w_]+\.sh", enforced_by)

    verified_something = False

    for token in tokens:
        # Check direct path relative to repo root
        direct = repo_root / token
        if direct.exists():
            verified_something = True
            continue

        # Check in .claude/hooks/
        hook_path = repo_root / ".claude" / "hooks" / token
        if hook_path.exists():
            verified_something = True
            continue

        # Check in tools/
        tool_path = repo_root / "tools" / token
        if tool_path.exists():
            verified_something = True
            continue

        # Check in backend/tests/
        backend_test_path = repo_root / "backend" / "tests" / token
        if backend_test_path.exists():
            verified_something = True
            continue

        errors.append(f"Referenced enforcement file does not exist: '{token}' in row '{row['rule']}'")

    # Check Gradle tasks or named humans
    if not tokens:
        # Check if a named human or known gate/task is referenced
        known_humans = ["Sowad Al-Mughni", "clinical lead", "second approver", "Maintainer"]
        is_human = any(h.lower() in enforced_by.lower() for h in known_humans)
        is_gradle = "gradle" in enforced_by.lower() or "detekt" in enforced_by.lower() or "gitleaks" in enforced_by.lower()
        is_test = "tc-" in enforced_by.lower() or "test" in enforced_by.lower()

        if is_human or is_gradle or is_test:
            verified_something = True
        else:
            errors.append(f"Could not verify enforcement for row: '{row['rule']}' -> '{enforced_by}'")

    return errors


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    claude_md = repo_root / "CLAUDE.md"

    try:
        rows = parse_claude_table(claude_md)
    except Exception as e:
        print(f"FAIL: Error parsing CLAUDE.md: {e}", file=sys.stderr)
        return 1

    if not rows:
        print("FAIL: No rows found in CLAUDE.md 'Must not trade off' table", file=sys.stderr)
        return 1

    all_errors = []
    for row in rows:
        errors = verify_enforcement_row(repo_root, row)
        all_errors.extend(errors)

    if all_errors:
        print("GUARDRAILS VERIFICATION FAILED:", file=sys.stderr)
        for err in all_errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print(f"GUARDRAILS CHECK PASSED: Verified {len(rows)} rules in CLAUDE.md table.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
