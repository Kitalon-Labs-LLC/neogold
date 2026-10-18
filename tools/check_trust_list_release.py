#!/usr/bin/env python3
"""Release gate check: verifies that config/trust-list.json contains NO test-only keys.

Usage: python3 tools/check_trust_list_release.py
Fails (exit 1) if any key in config/trust-list.json is marked test_only or has a TEST- prefix.
Exits 0 if all keys are genuine production keys.
"""

import json
import sys
from pathlib import Path


def check_trust_list_for_release(trust_list_path: Path) -> tuple[bool, list[str]]:
    if not trust_list_path.exists():
        return False, [f"Trust list file not found: {trust_list_path}"]

    try:
        with open(trust_list_path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        return False, [f"Failed to read/parse JSON from {trust_list_path}: {e}"]

    violations = []
    keys = data.get("keys", [])
    if not keys:
        violations.append("Trust list has no keys defined.")

    for k in keys:
        kid = k.get("key_id", "")
        # Check test_only boolean flag
        if k.get("test_only") is True:
            violations.append(f"Key '{kid}' has 'test_only: true'")
        # Check test prefixes/conventions
        if kid.upper().startswith("TEST-") or kid.upper().startswith("TEST_"):
            violations.append(f"Key '{kid}' has test key_id prefix")
        desc = k.get("description", "").lower()
        if "test-only" in desc or "test only" in desc:
            violations.append(f"Key '{kid}' description indicates test-only: '{k.get('description')}'")

    return len(violations) == 0, violations


def main():
    root = Path(__file__).resolve().parent.parent
    trust_list_path = root / "config" / "trust-list.json"
    passed, violations = check_trust_list_for_release(trust_list_path)

    if not passed:
        print("FAIL: Release gate check failed for config/trust-list.json:", file=sys.stderr)
        for v in violations:
            print(f"  - {v}", file=sys.stderr)
        print("\nRelease builds MUST NOT contain test keys. Replace with production keys before release.", file=sys.stderr)
        return 1

    print("PASS: config/trust-list.json contains zero test-only keys.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
