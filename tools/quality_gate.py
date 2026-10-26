#!/usr/bin/env python3
"""
tools/quality_gate.py

Enforces P25 Quality Tiers (The Slop Budget):
Inspects git diff against a base ref, determines the strictest tier touched (Tier 0 to Tier 3),
and outputs/enforces the required checks for that tier.

Usage:
  python3 tools/quality_gate.py [--base origin/main] [--pr-body <path>]
"""

import argparse
import fnmatch
import os
import subprocess
import sys
from pathlib import Path
import yaml

TIER_NAMES = {
    0: "Tier 0 (Clinical)",
    1: "Tier 1 (Data & Trust)",
    2: "Tier 2 (App)",
    3: "Tier 3 (Tooling)",
}

REQUIRED_CHECKS = {
    0: [
        "Unit tests (testReleaseUnitTest)",
        "Golden vectors (verifyGoldenVectors)",
        "Differential triage (triageDifferentialTest, when :engine:triage or config touched)",
        "Branch coverage floor (85% on pure JVM engines)",
        "StrictMath verification (verifyStrictMath)",
        "Mutation check (P03 manual mutation checklist documented in PR)",
        "Safety-reviewer section in PR body",
    ],
    1: [
        "Lint and tests (backend tests, security unit tests)",
        "Privacy-security-reviewer section in PR body",
        "Clean P26 security scan (gitleaks, Trivy, bandit)",
    ],
    2: [
        "Lint (lintRelease, ruff, detekt)",
        "Tests (unit tests)",
        "Screenshot tests for UI (when UI screens touched)",
    ],
    3: [
        "Lint and tooling tests (tools/tests/)",
    ],
}


def load_tier_definitions(tiers_path: Path) -> dict[int, list[str]]:
    with open(tiers_path, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    result = {}
    for tier_num, spec in data.get("tiers", {}).items():
        result[int(tier_num)] = spec.get("paths", [])
    return result


def match_path_to_tier(path_str: str, tiers_map: dict[int, list[str]]) -> int:
    path_norm = path_str.replace("\\", "/")
    # Check from Tier 0 upwards
    for tier in (0, 1, 2):
        patterns = tiers_map.get(tier, [])
        for pattern in patterns:
            # Handle glob patterns with **
            if fnmatch.fnmatch(path_norm, pattern):
                return tier
            # Also check prefix if pattern ends with **
            if pattern.endswith("/**"):
                prefix = pattern[:-3]
                if path_norm.startswith(prefix + "/") or path_norm == prefix:
                    return tier
    return 3  # Default to Tier 3 (Tooling / general)


def get_changed_files(repo_root: Path, base_ref: str) -> list[str]:
    # Try git diff --name-only <base_ref>...HEAD
    try:
        proc = subprocess.run(
            ["git", "diff", "--name-only", f"{base_ref}...HEAD"],
            cwd=repo_root,
            capture_output=True,
            text=True,
            check=True,
        )
        files = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
        if files:
            return files
    except Exception:
        pass

    # Fallback to git diff HEAD~1
    try:
        proc = subprocess.run(
            ["git", "diff", "--name-only", "HEAD~1"],
            cwd=repo_root,
            capture_output=True,
            text=True,
            check=True,
        )
        return [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    except Exception:
        return []


def verify_pr_body(tier: int, pr_body_text: str) -> list[str]:
    errors = []
    lower_body = pr_body_text.lower()
    if tier == 0:
        if "safety-reviewer" not in lower_body and "safety reviewer" not in lower_body:
            errors.append("PR body lacks required 'Safety Reviewer' section for Tier 0 (Clinical) changes.")
    if tier <= 1:
        if "privacy-security-reviewer" not in lower_body and "privacy" not in lower_body:
            errors.append("PR body lacks required 'Privacy / Security Reviewer' section for Tier 1 changes.")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate PR changes against NeoGold quality tiers")
    parser.add_argument("--base", default=None, help="Base git ref (default: origin/main or dev)")
    parser.add_argument("--pr-body", default=None, help="Path to file containing PR body text")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    tiers_path = repo_root / "quality" / "tiers.yaml"

    if not tiers_path.exists():
        print(f"FAIL: Tiers configuration not found at {tiers_path}", file=sys.stderr)
        return 1

    tiers_map = load_tier_definitions(tiers_path)

    base_ref = args.base
    if not base_ref:
        base_ref = os.environ.get("GITHUB_BASE_REF", "origin/main")

    changed_files = get_changed_files(repo_root, base_ref)
    if not changed_files:
        print("QUALITY GATE: No changed files detected against base ref.")
        return 0

    touched_tiers = {}
    for f in changed_files:
        tier = match_path_to_tier(f, tiers_map)
        touched_tiers[f] = tier

    strictest_tier = min(touched_tiers.values())
    strictest_name = TIER_NAMES.get(strictest_tier, f"Tier {strictest_tier}")

    print("=" * 60)
    print(f"QUALITY GATE: Strictest tier touched is {strictest_name}")
    print("=" * 60)
    print(f"Changed files ({len(changed_files)} total):")
    for f, t in sorted(touched_tiers.items()):
        print(f"  [{t}] {f}")

    print(f"\nRequired checks for {strictest_name}:")
    for chk in REQUIRED_CHECKS.get(strictest_tier, []):
        print(f"  [x] {chk}")

    errors = []
    if args.pr_body and Path(args.pr_body).exists():
        pr_text = Path(args.pr_body).read_text(encoding="utf-8")
        errors.extend(verify_pr_body(strictest_tier, pr_text))

    if errors:
        print("\nQUALITY GATE VIOLATIONS:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print("\nQUALITY GATE: Tier requirements evaluated successfully.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
