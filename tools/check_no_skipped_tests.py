#!/usr/bin/env python3
"""
tools/check_no_skipped_tests.py

Enforces P25 / Doc 9 Section 7:
1. No skipped tests in Tier 0 and Tier 1 test paths:
   Fails on @Ignore, @Disabled, assumeTrue(false), pytest.mark.skip, xfail, or pytest.skip(.
2. No drop in per-TC test count against quality/tc-baseline.json:
   Every TC must maintain at least as many citing tests as recorded in the baseline.
"""

import json
import re
import sys
from pathlib import Path

# Match skip markers in test code
SKIP_MARKERS = [
    (re.compile(r"@Ignore\b"), "@Ignore"),
    (re.compile(r"@Disabled\b"), "@Disabled"),
    (re.compile(r"assumeTrue\s*\(\s*false\s*\)"), "assumeTrue(false)"),
    (re.compile(r"assumeFalse\s*\(\s*true\s*\)"), "assumeFalse(true)"),
    (re.compile(r"@?pytest\.mark\.skip\b"), "pytest.mark.skip"),
    (re.compile(r"@?pytest\.mark\.xfail\b"), "pytest.mark.xfail"),
    (re.compile(r"\bpytest\.skip\s*\("), "pytest.skip("),
    (re.compile(r"\bpytest\.xfail\s*\("), "pytest.xfail("),
]

TIER0_AND_1_GLOBS = [
    # Tier 0 (Clinical)
    "android/engine/triage/src/test/**",
    "android/engine/estimation/src/test/**",
    "android/engine/colorscience/src/test/**",
    "android/engine/cardtrust/src/test/**",
    "android/engine/vision/src/test/**",
    "android/feature/result/src/test/**",
    # Tier 1 (Data & Trust)
    "android/core/security/src/test/**",
    "android/core/data/src/test/**",
    "android/sync/research/src/test/**",
    "tools/neogold-decrypt/src/test/**",
    "backend/tests/**",
]


def find_skips_in_content(content: str) -> list[tuple[int, str]]:
    violations = []
    for line_idx, line in enumerate(content.splitlines(), 1):
        trimmed = line.strip()
        if trimmed.startswith("//") or trimmed.startswith("#") or trimmed.startswith("*") or trimmed.startswith("/*"):
            continue
        for regex, name in SKIP_MARKERS:
            if regex.search(line):
                violations.append((line_idx, name))
    return violations


def check_skipped_markers(repo_root: Path) -> list[str]:
    violations = []
    seen = set()

    for pattern in TIER0_AND_1_GLOBS:
        for file_path in repo_root.glob(pattern):
            if not file_path.is_file() or file_path in seen or "/build/" in file_path.as_posix():
                continue
            seen.add(file_path)
            try:
                content = file_path.read_text(encoding="utf-8")
            except Exception:
                continue

            rel_path = file_path.relative_to(repo_root).as_posix()
            for line_idx, name in find_skips_in_content(content):
                violations.append(f"{rel_path}:{line_idx}: Found skip marker '{name}'")
    return violations



def check_tc_baseline(repo_root: Path) -> list[str]:
    violations = []
    baseline_path = repo_root / "quality" / "tc-baseline.json"
    if not baseline_path.exists():
        return [f"Baseline file missing: {baseline_path}"]

    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))

    # Import count_tests from trace.py
    sys.path.insert(0, str(repo_root / "tools"))
    try:
        from trace import count_tests, test_cases
    except ImportError as e:
        return [f"Failed to import trace utilities: {e}"]

    tc_doc = repo_root / "docs" / "10_Test_Cases.md"
    if not tc_doc.exists():
        return [f"Missing {tc_doc}"]

    cases, _ = test_cases(tc_doc.read_text(encoding="utf-8"))
    current_sites = count_tests(repo_root, cases)
    current_counts = {tcid: len(sites) for tcid, sites in current_sites.items()}

    for tcid, base_count in baseline.items():
        curr_count = current_counts.get(tcid, 0)
        if curr_count < base_count:
            violations.append(
                f"Test count drop for {tcid}: baseline is {base_count}, but current test count is {curr_count}"
            )

    return violations


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    errors = []

    marker_violations = check_skipped_markers(repo_root)
    errors.extend(marker_violations)

    baseline_violations = check_tc_baseline(repo_root)
    errors.extend(baseline_violations)

    if errors:
        print("NO-SKIPPED-TESTS CHECK FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print("NO-SKIPPED-TESTS CHECK PASSED: Zero skipped tests in Tier 0/1 and all TC counts at or above baseline.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
