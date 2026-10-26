#!/usr/bin/env python3
"""
tools/check_strings.py

Enforces:
1. TC-A11Y-01: Every string translated (no missing keys between values/ and values-bn/).
2. TC-UI-04: No banned words from doc 6 Section 10.2 in English or Bangla.
3. No hardcoded string violations in layouts/code where strings.xml keys should be used.
   Implemented as a ratchet for Kotlin sources (section 4 below; BUG-2026-029).

Usage: python3 tools/check_strings.py [--update-baseline]
"""

import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BANNED_ENGLISH = [
    r"\bsafe\b",
    r"\bnormal\b",
    r"\bhealthy\b",
    r"\bfine\b",
    r"\bno jaundice\b",
    r"\bdiagnosis\b",
    r"\bdiagnose\b",
    r"\baccurate\b",
    r"\bprecise\b",
    r"\bai\b",
    r"\bartificial intelligence\b",
]

BANNED_BANGLA = [
    r"নিরাপদ",
    r"(?<!অ)স্বাভাবিক",
    r"সুস্থ",
    r"ঠিক আছে",
    r"জন্ডিস নেই",
    r"রোগ নির্ণয়",
    r"নির্ভুল",
    r"সঠিক মাপ",
    r"এআই",
    r"কৃত্রিম বুদ্ধিমত্তা",
]


def parse_strings(xml_path):
    tree = ET.parse(xml_path)
    root = tree.getroot()
    strings = {}
    for elem in root.findall("string"):
        name = elem.get("name")
        text = "".join(elem.itertext()).strip()
        strings[name] = text
    return strings


def check_strings_content(en_strings: dict[str, str], bn_strings: dict[str, str]) -> list[str]:
    """Check parsed strings for missing keys and banned words."""
    errors = []

    # 1. Missing keys check (TC-A11Y-01)
    missing_in_bn = set(en_strings.keys()) - set(bn_strings.keys())
    missing_in_en = set(bn_strings.keys()) - set(en_strings.keys())

    if missing_in_bn:
        errors.append(f"Missing in values-bn/strings.xml: {sorted(missing_in_bn)}")
    if missing_in_en:
        errors.append(f"Missing in values/strings.xml: {sorted(missing_in_en)}")

    # 2. English banned words check (TC-UI-04)
    for key, text in en_strings.items():
        for pat in BANNED_ENGLISH:
            if re.search(pat, text, re.IGNORECASE):
                errors.append(f"English string '{key}' contains banned pattern '{pat}': \"{text}\"")

    # 3. Bangla banned words check (TC-UI-04)
    for key, text in bn_strings.items():
        for pat in BANNED_BANGLA:
            if re.search(pat, text):
                errors.append(f"Bangla string '{key}' contains banned pattern '{pat}': \"{text}\"")

    return errors


# --- 4. Hardcoded UI literals in Kotlin (BUG-2026-029, TC-A11Y-01) ---------------------------
#
# Every user-visible string belongs in values/ and values-bn/ (NFR-06). About 150 literals were
# written before this check existed, so the lint is a ratchet: each file may keep the count in
# tools/strings_baseline.json but never gain one, and the baseline must shrink as literals move.
# Heuristic, not a Kotlin parser: it looks at the Compose and notification APIs below.

LITERAL_START = re.compile(
    r'(?:\bText\s*\(\s*(?:text\s*=\s*)?'
    r'|\b(?:text|contentDescription|title|label|placeholder|supportingText)\s*=\s*'
    r'|\.(?:setContentTitle|setContentText|setTicker|setSubText)\s*\(\s*)"'
)
INTERPOLATION = re.compile(r"\$\{[^}]*\}|\$[A-Za-z_]\w*")
SKIPPED_DIRS = {"build", ".gradle", ".git", "node_modules"}
BASELINE_PATH = os.path.join("tools", "strings_baseline.json")


def _read_literal(source, start):
    """Return the text of the string literal whose first character is source[start], or None."""
    if source.startswith('""', start):  # raw string: """..."""
        end = source.find('"""', start + 2)
        return None if end < 0 else source[start + 2:end]
    chars = []
    i = start
    while i < len(source):
        c = source[i]
        if c == "\\":
            chars.append(source[i:i + 2])
            i += 2
            continue
        if c == '"':
            return "".join(chars)
        if c == "\n":
            return None
        chars.append(c)
        i += 1
    return None


def count_literals_in_source(source):
    """Count hardcoded, translatable-looking literals passed to UI and notification APIs."""
    count = 0
    for match in LITERAL_START.finditer(source):
        line_start = source.rfind("\n", 0, match.start()) + 1
        if source[line_start:match.start()].lstrip().startswith(("//", "/*", "*")):
            continue
        literal = _read_literal(source, match.end())
        if literal is None:
            continue
        text = INTERPOLATION.sub("", literal)
        if any(ch.isalpha() for ch in text):  # symbols, digits and pure interpolation are not text
            count += 1
    return count


def scan_kotlin_literals(repo_root):
    """Map repo-relative posix path -> literal count, for every android/**/src/main/**/*.kt with any."""
    counts = {}
    android_root = os.path.join(str(repo_root), "android")
    for dirpath, dirnames, filenames in os.walk(android_root):
        dirnames[:] = [d for d in dirnames if d not in SKIPPED_DIRS]
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, str(repo_root)).replace(os.sep, "/")
            if "/src/main/" not in rel:
                continue
            with open(full, encoding="utf-8", errors="replace") as f:
                n = count_literals_in_source(f.read())
            if n:
                counts[rel] = n
    return counts


def check_literal_ratchet(counts, baseline):
    """Compare current counts with the baseline; every difference is a problem."""
    problems = []
    for path in sorted(set(counts) | set(baseline)):
        now, allowed = counts.get(path, 0), baseline.get(path, 0)
        if now > allowed:
            problems.append(
                f"{path} has {now} hardcoded UI string literal(s) but the baseline allows {allowed}. "
                "Put the text in strings.xml (values/ and values-bn/) and use stringResource()."
            )
        elif now < allowed:
            problems.append(
                f"{path} now has {now} literal(s) but the baseline still says {allowed}. "
                "Tighten it with: python3 tools/check_strings.py --update-baseline"
            )
    return problems


def load_baseline(repo_root):
    path = os.path.join(str(repo_root), BASELINE_PATH)
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return dict(json.load(f).get("files", {}))


def write_baseline(repo_root, counts):
    path = os.path.join(str(repo_root), BASELINE_PATH)
    document = {
        "schema": 1,
        "note": "Hardcoded UI string literals per Kotlin file (tools/check_strings.py). "
                "May only shrink. Regenerate with: python3 tools/check_strings.py --update-baseline",
        "files": dict(sorted(counts.items())),
    }
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(document, f, indent=2, ensure_ascii=False)
        f.write("\n")


def main():
    update_baseline = "--update-baseline" in sys.argv[1:]
    repo_root = Path(__file__).resolve().parent.parent
    res_dirs = [
        repo_root / "android" / "core" / "designsystem" / "src" / "main" / "res",
    ]

    total_en = 0
    total_bn = 0
    all_errors = []

    for res_dir in res_dirs:
        en_xml = res_dir / "values" / "strings.xml"
        bn_xml = res_dir / "values-bn" / "strings.xml"

        if not en_xml.exists() or not bn_xml.exists():
            print(f"ERROR: String resource files not found in {res_dir}", file=sys.stderr)
            sys.exit(1)

        en_strings = parse_strings(en_xml)
        bn_strings = parse_strings(bn_xml)
        total_en += len(en_strings)
        total_bn += len(bn_strings)

        errors = check_strings_content(en_strings, bn_strings)
        all_errors.extend(errors)

    # 4. Hardcoded UI literals in Kotlin (BUG-2026-029): a ratchet that can only shrink
    literal_counts = scan_kotlin_literals(repo_root)
    if update_baseline:
        write_baseline(repo_root, literal_counts)
        print(f"Baseline updated: {sum(literal_counts.values())} literal(s) in {len(literal_counts)} file(s).")
    else:
        all_errors.extend(check_literal_ratchet(literal_counts, load_baseline(repo_root)))

    if all_errors:
        print("STRING LINT VIOLATIONS DETECTED:", file=sys.stderr)
        for err in all_errors:
            print(f"  - {err}", file=sys.stderr)
        sys.exit(1)

    print(
        f"SUCCESS: String lint passed! Checked {total_en} English and {total_bn} Bangla strings. "
        f"0 violations. Hardcoded Kotlin literals: {sum(literal_counts.values())} in {len(literal_counts)} file(s) (ratchet, baseline only shrinks)."
    )
    sys.exit(0)


if __name__ == "__main__":
    main()
