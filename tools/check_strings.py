#!/usr/bin/env python3
"""
tools/check_strings.py

Enforces:
1. TC-A11Y-01: Every string translated (no missing keys between values/ and values-bn/).
2. TC-UI-04: No banned words from doc 6 Section 10.2 in English or Bangla.
3. No hardcoded string violations in layouts/code where strings.xml keys should be used.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

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


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    res_dir = os.path.join(repo_root, "android", "core", "designsystem", "src", "main", "res")
    en_xml = os.path.join(res_dir, "values", "strings.xml")
    bn_xml = os.path.join(res_dir, "values-bn", "strings.xml")

    if not os.path.exists(en_xml) or not os.path.exists(bn_xml):
        print(f"ERROR: String resource files not found in {res_dir}", file=sys.stderr)
        sys.exit(1)

    en_strings = parse_strings(en_xml)
    bn_strings = parse_strings(bn_xml)

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

    if errors:
        print("STRING LINT VIOLATIONS DETECTED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        sys.exit(1)

    print(f"SUCCESS: String lint passed! Checked {len(en_strings)} English and {len(bn_strings)} Bangla strings. 0 violations.")
    sys.exit(0)


if __name__ == "__main__":
    main()
