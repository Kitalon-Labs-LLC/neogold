#!/usr/bin/env python3
"""
tools/check_strings.py

Enforces:
1. TC-A11Y-01: Every string translated (no missing keys between values/ and values-bn/).
2. TC-UI-04: No banned words from doc 6 Section 10.2 in English or Bangla.
3. No hardcoded string violations in layouts/code where strings.xml keys should be used.
   Implemented as a ratchet for Kotlin sources (section 4 below; BUG-2026-029).
4. A number placeholder (%d, %f) is never given a String (section 5 below; BUG-2026-067): the call throws
   IllegalFormatConversionException when the screen opens, which no unit test of the logic can see.

Usage: python3 tools/check_strings.py [--update-baseline]
"""

import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Doc 6 Section 10.2. The patterns match the word and its inflections (diagnosed, accuracy, normally, safely), not only the
# whole word (BUG-2026-091): "Accuracy" and "diagnostic" overclaim just as "accurate" and "diagnose" do. "Safety" and "health"
# stay allowed: they are not the banned claims.
BANNED_ENGLISH = [
    r"\bsafe(?:r|st|ly)?\b",
    r"\bnormal(?:ly)?\b",
    r"\bhealthy\b",
    r"\bfine\b",
    r"\bno jaundice\b",
    r"\bdiagnos\w*",
    r"\baccura\w*",
    r"\bprecis\w*",
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
    for plural in root.findall("plurals"):
        for item in plural.findall("item"):
            strings[f"{plural.get('name')}[{item.get('quantity')}]"] = "".join(item.itertext()).strip()
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


def ui_literals_in_source(source):
    """The hardcoded, translatable-looking literals passed to UI and notification APIs, as (line number, text)."""
    found = []
    for match in LITERAL_START.finditer(source):
        line_start = source.rfind("\n", 0, match.start()) + 1
        if source[line_start:match.start()].lstrip().startswith(("//", "/*", "*")):
            continue
        literal = _read_literal(source, match.end())
        if literal is None:
            continue
        text = INTERPOLATION.sub("", literal)
        if any(ch.isalpha() for ch in text):  # symbols, digits and pure interpolation are not text
            found.append((source.count("\n", 0, match.start()) + 1, literal))
    return found


def count_literals_in_source(source):
    """Count hardcoded, translatable-looking literals passed to UI and notification APIs."""
    return len(ui_literals_in_source(source))


def banned_words_in_literals(source, label="<source>"):
    """Errors for each UI literal in Kotlin that holds a banned word: the ratchet allows a literal to stay, never a banned one."""
    errors = []
    for line, literal in ui_literals_in_source(source):
        for pat in BANNED_ENGLISH:
            if re.search(pat, literal, re.IGNORECASE):
                errors.append(f"{label}:{line}: UI literal contains banned pattern '{pat}': \"{literal}\"")
        for pat in BANNED_BANGLA:
            if re.search(pat, literal):
                errors.append(f"{label}:{line}: UI literal contains banned pattern '{pat}': \"{literal}\"")
    return errors


def check_literals_banned(repo_root):
    """Banned words in every UI literal of every module's main sources (TC-UI-04 over Kotlin, not only strings.xml)."""
    errors = []
    android_root = os.path.join(str(repo_root), "android")
    for dirpath, dirnames, filenames in os.walk(android_root):
        dirnames[:] = [d for d in dirnames if d not in SKIPPED_DIRS]
        for name in filenames:
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, str(repo_root)).replace(os.sep, "/")
            if not name.endswith(".kt") or "/src/main/" not in rel:
                continue
            with open(full, encoding="utf-8", errors="replace") as f:
                errors.extend(banned_words_in_literals(f.read(), rel))
    return errors


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


# --- 5. Arguments of format strings (BUG-2026-067) -------------------------------------------------------------------
# `stringResource(R.string.x, "4,096")` with x = "Memory: %d MB" compiles and then crashes when the screen is drawn.
# Heuristic, not a Kotlin parser: it reads calls of stringResource and getString whose first argument is R.string.<key>.

FORMAT_SPEC = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")
FORMAT_CALL = re.compile(r"(?:stringResource|getString)\(\s*(?:id\s*=\s*)?(?:[\w.]*\.)?R\.string\.(\w+)\s*(?=[,)])")
NUMBER_CONVERSIONS = set("dfxXeEgG")
STRING_LOOKING = re.compile(r'^"|\.format\w*\(|\bformat\w*\(|\.toString\(\)|String\.format\(|\$\{|(?:Text|Label|Str)$')


def placeholder_conversions(text):
    """The conversion letter of each placeholder, by argument position (0-based)."""
    found, sequential = {}, 0
    for m in FORMAT_SPEC.finditer(text.replace("%%", "")):
        index = int(m.group(1)) - 1 if m.group(1) else sequential
        if not m.group(1):
            sequential += 1
        found[index] = m.group(2)
    return found


def split_call_arguments(source, start):
    """The top-level arguments of the call whose argument list begins at source[start] (just after the first argument)."""
    args, depth, current, i, quote = [], 0, [], start, None
    while i < len(source):
        c = source[i]
        if quote:
            current.append(c)
            if c == "\\":
                current.append(source[i + 1] if i + 1 < len(source) else "")
                i += 1
            elif c == quote:
                quote = None
        elif c in "\"'":
            quote = c
            current.append(c)
        elif c in "([{":
            depth += 1
            current.append(c)
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
            current.append(c)
        elif c == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
        else:
            current.append(c)
        i += 1
    tail = "".join(current).strip()
    if tail:
        args.append(tail)
    return args


def check_format_arguments_in_source(source, formats, label="<source>"):
    """Errors for each call that gives a String-looking argument to a number placeholder of a known format string."""
    errors = []
    for m in FORMAT_CALL.finditer(source):
        key = m.group(1)
        conversions = formats.get(key)
        if not conversions:
            continue
        rest = source[m.end():]
        args = split_call_arguments(rest.lstrip(",") if rest.startswith(",") else rest, 0)
        for index, conversion in conversions.items():
            if conversion not in NUMBER_CONVERSIONS or index >= len(args):
                continue
            if STRING_LOOKING.search(args[index]):
                errors.append(
                    f"{label}: stringResource(R.string.{key}) passes a String ({args[index][:40]}) to the "
                    f"%{conversion} placeholder in argument {index + 1}; pass the number itself (BUG-2026-067)"
                )
    return errors


def format_strings_of(res_dirs):
    formats = {}
    for res_dir in res_dirs:
        for name, text in parse_strings(res_dir / "values" / "strings.xml").items():
            conversions = placeholder_conversions(text)
            if conversions:
                formats[name] = conversions
    return formats


def check_format_arguments(repo_root, res_dirs):
    formats = format_strings_of(res_dirs)
    errors = []
    for dirpath, dirnames, filenames in os.walk(os.path.join(str(repo_root), "android")):
        dirnames[:] = [d for d in dirnames if d not in SKIPPED_DIRS]
        posix = dirpath.replace(os.sep, "/")
        if "/src/main/" not in posix + "/":
            continue
        for name in filenames:
            if name.endswith(".kt"):
                full = os.path.join(dirpath, name)
                with open(full, encoding="utf-8", errors="replace") as f:
                    rel = os.path.relpath(full, str(repo_root)).replace(os.sep, "/")
                    errors.extend(check_format_arguments_in_source(f.read(), formats, rel))
    return errors


def find_res_dirs(repo_root):
    """Every android/**/src/main/res folder that has a values/strings.xml, so a module's own strings are linted too."""
    found = []
    for dirpath, dirnames, filenames in os.walk(os.path.join(str(repo_root), "android")):
        dirnames[:] = [d for d in dirnames if d not in SKIPPED_DIRS]
        posix = dirpath.replace(os.sep, "/")
        if posix.endswith("/src/main/res/values") and "strings.xml" in filenames:
            found.append(Path(dirpath).parent)
    return sorted(found)


def main():
    update_baseline = "--update-baseline" in sys.argv[1:]
    repo_root = Path(__file__).resolve().parent.parent
    res_dirs = find_res_dirs(repo_root)

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

    # 5. A String given to a number placeholder (BUG-2026-067)
    all_errors.extend(check_format_arguments(repo_root, res_dirs))

    # 2b. Banned words in the UI literals still in Kotlin (BUG-2026-091)
    all_errors.extend(check_literals_banned(repo_root))

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
