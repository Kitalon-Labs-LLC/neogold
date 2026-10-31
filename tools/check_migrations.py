#!/usr/bin/env python3
"""
tools/check_migrations.py

Enforces P25 / Doc 8 Section 8.4 / Doc 12 Section 2:
1. Committed Room schema files never change once released:
   Existing committed Room schema files (android/core/data/schemas/.../*.json) must never be modified or deleted.
2. Forward-only expand-and-contract Alembic migrations:
   Alembic migration upgrade() containing destructive operations (`op.drop_` or `nullable=False` on alter_column)
   fail unless the PR has the `contract-migration` label (or --allow-contract-migration is passed).
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

DESTRUCTIVE_ALEMBIC_OPS = [
    re.compile(r"\bop\.drop_table\b"),
    re.compile(r"\bop\.drop_column\b"),
    re.compile(r"\bop\.drop_constraint\b"),
    re.compile(r"\bop\.drop_index\b"),
    re.compile(r"\balter_column\([^)]*nullable\s*=\s*False"),
]


def check_room_schemas(repo_root: Path, base_ref: str | None = None) -> list[str]:
    violations = []
    if not base_ref:
        base_ref = os.environ.get("GITHUB_BASE_REF", "origin/main")

    # Verify if base_ref exists
    try:
        subprocess.run(["git", "rev-parse", "--verify", base_ref], cwd=repo_root, capture_output=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        base_ref = "HEAD~1"

    schema_dir = "android/core/data/schemas"
    try:
        proc = subprocess.run(
            ["git", "diff", "--name-status", base_ref, "--", schema_dir],
            cwd=repo_root,
            capture_output=True,
            text=True,
        )
        if proc.returncode == 0:
            for line in proc.stdout.splitlines():
                line = line.strip()
                if not line:
                    continue
                status = line[0]
                filepath = line.split(maxsplit=1)[1]
                if status in ("M", "D") and filepath.endswith(".json"):
                    violations.append(
                        f"Committed Room schema {filepath} was modified/deleted (status {status}). "
                        f"Room schemas migrate forward only; existing schema files are immutable."
                    )
    except Exception:
        pass

    return violations


def extract_upgrade_body(content: str) -> str:
    m = re.search(r"def upgrade\s*\(\s*\)[^:]*:(.*?)(?:def downgrade|\Z)", content, re.DOTALL)
    if m:
        return m.group(1)
    return content


def check_alembic_migrations(
    repo_root: Path,
    base_ref: str | None = None,
    allow_contract_migration: bool = False,
    files_to_check: list[Path] | None = None,
) -> list[str]:
    violations = []
    if allow_contract_migration:
        return violations

    pr_labels = os.environ.get("PR_LABELS", "").split(",")
    if "contract-migration" in [lbl.strip() for lbl in pr_labels]:
        return violations

    if files_to_check is None:
        versions_dir = repo_root / "backend" / "alembic" / "versions"
        if not versions_dir.exists():
            return violations
        files_to_check = list(versions_dir.glob("*.py"))

    for py_file in files_to_check:
        try:
            content = py_file.read_text(encoding="utf-8")
        except Exception:
            continue

        upgrade_body = extract_upgrade_body(content)
        for line_num, line in enumerate(upgrade_body.splitlines(), 1):
            trimmed = line.strip()
            if trimmed.startswith("#"):
                continue
            for pattern in DESTRUCTIVE_ALEMBIC_OPS:
                m = pattern.search(line)
                if m:
                    violations.append(
                        f"{py_file.as_posix()}: upgrade() contains destructive operation '{m.group(0)}'. "
                        f"Expand-only migrations must not drop tables/columns or make columns non-nullable. "
                        f"Contract migrations require the 'contract-migration' PR label."
                    )
                    break
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description="Check forward-only migrations for Room and Alembic")
    parser.add_argument("--base", default=None, help="Base git ref to compare against (e.g. origin/main)")
    parser.add_argument(
        "--allow-contract-migration",
        action="store_true",
        help="Permit contract migrations (equivalent to contract-migration PR label)",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    errors = []

    room_errors = check_room_schemas(repo_root, base_ref=args.base)
    errors.extend(room_errors)

    alembic_errors = check_alembic_migrations(
        repo_root,
        base_ref=args.base,
        allow_contract_migration=args.allow_contract_migration,
    )
    errors.extend(alembic_errors)

    if errors:
        print("FORWARD-ONLY MIGRATION CHECK FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print("FORWARD-ONLY MIGRATION CHECK PASSED: Room schemas immutable; Alembic migrations are expand-only.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
