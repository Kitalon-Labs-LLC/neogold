#!/usr/bin/env python3
"""
TC-OPS-08: License and repository compliance checker.
Validates presence and correct license terms for project source, card design, and docs.
P00 provides the baseline check; P16 completes third-party dependency scanning.
"""
from pathlib import Path
import sys

def check_file_contains(path: Path, expected_text: str, label: str) -> bool:
    if not path.is_file():
        print(f"FAIL [TC-OPS-08]: {label} file not found at {path}")
        return False
    content = path.read_text(encoding="utf-8")
    if expected_text not in content:
        print(f"FAIL [TC-OPS-08]: {label} does not contain expected string '{expected_text}'")
        return False
    print(f"PASS [TC-OPS-08]: {label} verified ({path})")
    return True

def main() -> int:
    root = Path(__file__).resolve().parent.parent
    errors = 0

    # 1. Root Code License: MIT
    if not check_file_contains(root / "LICENSE", "MIT License", "Root Code License"):
        errors += 1

    # 2. Card Design License: CC BY 4.0
    if not check_file_contains(root / "card" / "LICENSE", "Creative Commons Attribution 4.0", "Card License"):
        errors += 1

    # 3. Documentation License: CC BY 4.0
    if not check_file_contains(root / "docs" / "LICENSE", "Creative Commons Attribution 4.0", "Docs License"):
        errors += 1

    # Placeholder for third-party dependency lockfile checks (expanded in P16)
    print("INFO [TC-OPS-08]: Third-party dependency scanning deferred to P16 (pending lockfiles).")

    if errors > 0:
        print(f"\nFAILED with {errors} license check error(s).")
        return 1

    print("\nALL LICENSE CHECKS PASSED.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
