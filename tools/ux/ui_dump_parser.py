#!/usr/bin/env python3
"""UI dump XML sanitizer and label coordinate resolver for `tools/ux/drive.sh` (P28).

Enforces P28 Build 1 & Constraints:
- `see`: Lists visible text, content descriptions, bounds, and clickable flags from a UI dump,
  with `resource-id`, `class`, and `package` attributes strictly removed.
- `find-label`: Resolves the center `(x, y)` coordinates of a node matching `<label>` by `text`
  or `content-desc` so `drive.sh tap-text "<label>"` can tap it deterministically.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BOUNDS_RE = re.compile(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")


def parse_bounds(bounds_str: str) -> tuple[int, int, int, int] | None:
    m = BOUNDS_RE.match((bounds_str or "").strip())
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))


def bounds_center(bounds_str: str) -> tuple[int, int] | None:
    parsed = parse_bounds(bounds_str)
    if not parsed:
        return None
    x1, y1, x2, y2 = parsed
    return (x1 + x2) // 2, (y1 + y2) // 2


def extract_sanitized_nodes(xml_text: str) -> list[dict[str, str]]:
    """Parse uiautomator XML and return sanitized node dicts with NO resource-id, class, or package."""
    root = ET.fromstring(xml_text)
    results: list[dict[str, str]] = []

    for elem in root.iter():
        # Explicitly ignore resource-id, class, package
        text = (elem.attrib.get("text") or "").strip()
        desc = (elem.attrib.get("content-desc") or "").strip()
        bounds = (elem.attrib.get("bounds") or "").strip()
        clickable = (elem.attrib.get("clickable") or "false").strip().lower()
        enabled = (elem.attrib.get("enabled") or "true").strip().lower()
        checked = (elem.attrib.get("checked") or "false").strip().lower()
        scrollable = (elem.attrib.get("scrollable") or "false").strip().lower()
        focused = (elem.attrib.get("focused") or "false").strip().lower()

        if not text and not desc and clickable != "true" and scrollable != "true":
            continue

        item: dict[str, str] = {
            "text": text,
            "desc": desc,
            "bounds": bounds,
            "clickable": clickable,
            "enabled": enabled,
            "checked": checked,
            "scrollable": scrollable,
            "focused": focused,
        }
        results.append(item)

    return results


def format_see_output(xml_text: str) -> str:
    nodes = extract_sanitized_nodes(xml_text)
    if not nodes:
        return "(no visible UI elements)"

    lines: list[str] = []
    for idx, node in enumerate(nodes, start=1):
        parts: list[str] = [f"[{idx}]"]
        if node["text"]:
            parts.append(f'text="{node["text"]}"')
        if node["desc"]:
            parts.append(f'desc="{node["desc"]}"')
        parts.append(f'bounds={node["bounds"]}')
        flags: list[str] = [f'clickable={node["clickable"]}']
        if node["enabled"] == "false":
            flags.append("enabled=false")
        if node["checked"] == "true":
            flags.append("checked=true")
        if node["scrollable"] == "true":
            flags.append("scrollable=true")
        if node["focused"] == "true":
            flags.append("focused=true")
        parts.append(" ".join(flags))
        lines.append(" | ".join(parts))

    return "\n".join(lines)


def find_label_center(xml_text: str, label: str) -> tuple[int, int] | None:
    target = label.strip()
    if not target:
        return None

    nodes = extract_sanitized_nodes(xml_text)

    # Pass 1: exact match on text or desc (preferring clickable nodes first)
    for prefer_clickable in (True, False):
        for node in nodes:
            if prefer_clickable and node["clickable"] != "true":
                continue
            if node["text"] == target or node["desc"] == target:
                center = bounds_center(node["bounds"])
                if center:
                    return center

    # Pass 2: case-insensitive exact match
    target_lower = target.lower()
    for prefer_clickable in (True, False):
        for node in nodes:
            if prefer_clickable and node["clickable"] != "true":
                continue
            if node["text"].lower() == target_lower or node["desc"].lower() == target_lower:
                center = bounds_center(node["bounds"])
                if center:
                    return center

    # Pass 3: substring match
    for prefer_clickable in (True, False):
        for node in nodes:
            if prefer_clickable and node["clickable"] != "true":
                continue
            if (target_lower in node["text"].lower()) or (target_lower in node["desc"].lower()):
                center = bounds_center(node["bounds"])
                if center:
                    return center

    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Sanitize uiautomator XML or locate label coordinates.")
    subparsers = parser.add_subparsers(dest="cmd", required=True)

    p_see = subparsers.add_parser("see", help="Print sanitized UI dump")
    p_see.add_argument("xml_file", help="Path to uiautomator dump XML file")

    p_find = subparsers.add_parser("find-label", help="Find (x, y) center for a text/desc label")
    p_find.add_argument("xml_file", help="Path to uiautomator dump XML file")
    p_find.add_argument("label", help="Visible text or content description to locate")

    args = parser.parse_args()
    xml_path = Path(args.xml_file)
    if not xml_path.exists():
        print(f"Error: UI dump file not found: {xml_path}", file=sys.stderr)
        return 1

    xml_text = xml_path.read_text(encoding="utf-8")

    if args.cmd == "see":
        print(format_see_output(xml_text))
        return 0

    if args.cmd == "find-label":
        center = find_label_center(xml_text, args.label)
        if not center:
            print(f"Error: Label not found on screen: {args.label!r}", file=sys.stderr)
            return 1
        print(f"{center[0]} {center[1]}")
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())

