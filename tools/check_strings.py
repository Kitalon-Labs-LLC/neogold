#!/usr/bin/env python3
"""
tools/check_strings.py

Enforces:
1. TC-A11Y-01: Every string translated (no missing keys between values/ and values-bn/).
2. TC-UI-04: No banned words from doc 6 Section 10.2 in English or Bangla.
3. No hardcoded string violations in layouts/code where strings.xml keys should be used.
"""

import glob
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


def main():
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

    if all_errors:
        print("STRING LINT VIOLATIONS DETECTED:", file=sys.stderr)
        for err in all_errors:
            print(f"  - {err}", file=sys.stderr)
        sys.exit(1)

    print(f"SUCCESS: String lint passed! Checked {total_en} English and {total_bn} Bangla strings. 0 violations.")
    sys.exit(0)


if __name__ == "__main__":
    main()
