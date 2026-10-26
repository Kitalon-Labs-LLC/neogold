#!/usr/bin/env python3
"""
tools/check_actions_pinned.py

Enforces P25 / Doc 8 Section 10 / Doc 12 Section 3:
Every third-party GitHub Action in .github/workflows/ must be pinned to a full 40-character commit SHA.
Actions pinned by tags (e.g. @v4) or branches are strictly forbidden to prevent supply-chain poisoning.
"""

import glob
import re
import sys
from pathlib import Path

SHA_RE = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)


def check_workflow_file(workflow_path: Path) -> list[str]:
    violations = []
    lines = workflow_path.read_text(encoding="utf-8").splitlines()

    for line_idx, line in enumerate(lines, 1):
        stripped = line.strip()
        if stripped.startswith("- "):
            stripped = stripped[2:].strip()
        if not stripped.startswith("uses:"):
            continue

        raw_target = stripped.split("uses:", 1)[1].strip()
        # Strip inline comment
        target = raw_target.split("#")[0].strip().strip('"').strip("'")

        # Local action references starting with . or ./ are allowed
        if target.startswith("."):
            continue

        if "@" not in target:
            violations.append(f"{workflow_path.as_posix()}:{line_idx}: Action lacks pin: '{target}'")
            continue

        action, ref = target.split("@", 1)
        ref = ref.strip()
        if not SHA_RE.match(ref):
            violations.append(
                f"{workflow_path.as_posix()}:{line_idx}: Action '{action}' is pinned to '{ref}', "
                f"which is not a 40-character commit SHA"
            )

    return violations


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    workflow_dir = repo_root / ".github" / "workflows"

    if not workflow_dir.exists():
        print(f"FAIL: Workflows directory not found at {workflow_dir}", file=sys.stderr)
        return 1

    workflow_files = sorted(workflow_dir.glob("*.yml")) + sorted(workflow_dir.glob("*.yaml"))
    all_violations = []

    for wf in workflow_files:
        all_violations.extend(check_workflow_file(wf))

    if all_violations:
        print("ACTIONS PINNING CHECK FAILED:", file=sys.stderr)
        for v in all_violations:
            print(f"  - {v}", file=sys.stderr)
        return 1

    print(f"ACTIONS PINNING CHECK PASSED: Verified {len(workflow_files)} workflow files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
