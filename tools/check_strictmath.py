#!/usr/bin/env python3
"""
tools/check_strictmath.py

Enforces P25 / Doc 4 Section 4.2:
Pure engines (:engine:*) must use StrictMath for powers, roots, exponentials, logarithms,
and trigonometric functions so that arm64-v8a and armeabi-v7a give bit-identical results.
Bans:
- import java.lang.Math
- import kotlin.math for those functions (pow, sqrt, cbrt, exp, expm1, ln, ln1p, log, log10, log2,
  hypot, sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh), and import kotlin.math.*
- Call sites like Math.pow, Math.cbrt, Math.sin, Math.atan2, etc.
"""

import glob
import re
import sys
from pathlib import Path

_FUNCS = "pow|sqrt|cbrt|exp|expm1|log|log10|log1p|hypot|sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh"

FORBIDDEN_IMPORT_PATTERNS = [
    (re.compile(r"import\s+java\.lang\.Math\b"), "import java.lang.Math is forbidden; use java.lang.StrictMath"),
    (
        re.compile(r"import\s+kotlin\.math\.(" + _FUNCS + r"|ln|ln1p|log2)\b"),
        "import kotlin.math for powers, roots, exponentials, logarithms, or trigonometry is forbidden; "
        "use java.lang.StrictMath",
    ),
    (
        re.compile(r"import\s+kotlin\.math\.\*"),
        "import kotlin.math.* is forbidden; it hides sqrt, sin and the other functions from this check",
    ),
]

FORBIDDEN_CALL_PATTERNS = [
    (re.compile(r"\bMath\.(" + _FUNCS + r")\b"), "Math.<func> is forbidden; use StrictMath.<func>"),
]


def check_engine_file(file_path: Path) -> list[str]:
    violations = []
    text = file_path.read_text(encoding="utf-8")
    lines = text.splitlines()

    for line_num, line in enumerate(lines, 1):
        # Skip comments
        trimmed = line.strip()
        if trimmed.startswith("//") or trimmed.startswith("/*") or trimmed.startswith("*"):
            continue

        for pattern, msg in FORBIDDEN_IMPORT_PATTERNS:
            if pattern.search(line):
                violations.append(f"{file_path}:{line_num}: {msg} -> '{trimmed}'")

        for pattern, msg in FORBIDDEN_CALL_PATTERNS:
            if pattern.search(line):
                violations.append(f"{file_path}:{line_num}: {msg} -> '{trimmed}'")

    return violations


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    engine_dirs = [
        repo_root / "android" / "engine" / "colorscience",
        repo_root / "android" / "engine" / "estimation",
        repo_root / "android" / "engine" / "triage",
        repo_root / "android" / "engine" / "cardtrust",
        repo_root / "android" / "engine" / "vision",
        repo_root / "android" / "engine" / "camera",
    ]

    all_violations = []
    checked_count = 0

    for edir in engine_dirs:
        for kt_file in edir.glob("src/main/**/*.kt"):
            checked_count += 1
            all_violations.extend(check_engine_file(kt_file))

    if all_violations:
        print("STRICTMATH VIOLATIONS IN ENGINES:", file=sys.stderr)
        for v in all_violations:
            print(f"  - {v}", file=sys.stderr)
        return 1

    print(f"STRICTMATH CHECK PASSED: Verified {checked_count} engine Kotlin files across :engine:*")
    return 0


if __name__ == "__main__":
    sys.exit(main())
