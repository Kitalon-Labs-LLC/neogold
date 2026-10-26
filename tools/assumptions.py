#!/usr/bin/env python3
"""NeoGold Assumptions Scanner and Spike Prioritizer.

Scans design documents (docs/2 to docs/16) for [Likely] and [Guessing] confidence tags.
Assesses whether production code depends on the assumption, whether an existing test
proves it, evaluates impact severity (Clinical, Privacy, Security, Cost, Schedule),
and produces an audit report in reports/assumptions/YYYY-MM-DD.md with ready-to-run
/ng-spike commands.
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, List, Optional

IMPACT_WEIGHTS = {
    "Clinical": 50,
    "Clinical Safety": 50,
    "Clinical / UX": 45,
    "Privacy": 40,
    "Privacy / Loss": 40,
    "Security": 32,
    "Security / Schedule": 30,
    "Legal / Security": 28,
    "Cost / Hardware": 25,
    "Cost": 22,
    "Schedule / Sync": 20,
    "Testing Schedule": 16,
    "Schedule": 10,
    "Process": 5,
}

KNOWN_ASSUMPTIONS = [
    {
        "pattern": r"tone curve instead of assuming standard sRGB gamma",
        "file_hint": "4_System_Architecture.md",
        "claim": "Phone image processors do not follow standard sRGB gamma; empirical gray-ramp curve absorption is needed",
        "code": "android/engine/colorscience/src/main/java/org/neogold/colorscience/ToneCurveLinearizer.kt:25",
        "test": "TC-EST-02 (synthetic curve only; hardware tone curves unverified)",
        "proven": False,
        "impact": "Clinical",
        "spike_cmd": '/ng-spike "Phone image processors do not follow standard sRGB gamma so empirical gray-ramp linearization is required"',
    },
    {
        "pattern": r"Marker detection on preview frame.*under 80 ms",
        "file_hint": "4_System_Architecture.md",
        "claim": "Marker detection on preview frame runs in under 80 ms per frame at 5 fps",
        "code": "android/feature/camera/src/main/java/org/neogold/camera/CameraPreviewAnalyzer.kt:45",
        "test": "TC-DEV-03, TC-PERF-01 (measured in lab, target phone variation remains)",
        "proven": False,
        "impact": "Clinical / UX",
        "spike_cmd": '/ng-spike "Marker detection on preview frame runs in under 80 ms at 5 fps on low-end target devices"',
    },
    {
        "pattern": r"AE and AWB convergence.*under 1\.5 s",
        "file_hint": "4_System_Architecture.md",
        "claim": "AE and AWB convergence and lock completes within 1.5 seconds",
        "code": "android/feature/camera/src/main/java/org/neogold/camera/CameraEngine.kt:110",
        "test": "None (mock test only)",
        "proven": False,
        "impact": "Clinical / UX",
        "spike_cmd": '/ng-spike "AE and AWB convergence and lock completes within 1.5 seconds on target devices"',
    },
    {
        "pattern": r"Play review can take hours to days",
        "file_hint": "12_Deployment_Runbook.md",
        "claim": "Google Play review latency takes hours to days, preventing immediate hotfix kill switches without validUntil",
        "code": "android/engine/triage/src/main/java/org/neogold/triage/TriageConfig.kt:32",
        "test": "TC-TRI-25 (validUntil expiry enforced)",
        "proven": False,
        "impact": "Clinical Safety",
        "spike_cmd": '/ng-spike "Google Play closed testing review latency exceeds 4 hours preventing zero-notice emergency app halt"',
    },
    {
        "pattern": r"Budget phones can lose Keystore keys",
        "file_hint": "8_Security_Compliance_Plan.md",
        "claim": "Budget phones can lose Keystore keys during OS updates or factory resets, requiring paper register backup",
        "code": "android/core/crypto/src/main/java/org/neogold/crypto/KeystoreManager.kt:30",
        "test": "None (hardware lifecycle event)",
        "proven": False,
        "impact": "Privacy / Loss",
        "spike_cmd": '/ng-spike "Budget phones can silently drop Keystore keys during OS updates or factory resets"',
    },
    {
        "pattern": r"budget phones ship broken or revoked attestation chains",
        "file_hint": "7_API_Documentation.md",
        "claim": "Some budget phones ship broken or revoked Android Key Attestation chains, requiring non-blocking enrollment",
        "code": "backend/app/services/device_attestation.py:40",
        "test": "TC-API-07, TC-SYNC-02 (synthetic chains only)",
        "proven": False,
        "impact": "Security / Schedule",
        "spike_cmd": '/ng-spike "Budget Android phones frequently fail key attestation requiring non-blocking enrollment"',
    },
    {
        "pattern": r"SQLCipher for Android, community edition.*BSD-style",
        "file_hint": "8_Security_Compliance_Plan.md",
        "claim": "SQLCipher Community Edition license is BSD-style compatible with MIT distribution",
        "code": "tools/check_licenses.py:75",
        "test": "TC-OPS-08 (license check automated)",
        "proven": True,
        "impact": "Legal / Security",
        "spike_cmd": '/ng-spike "SQLCipher Community Edition license is BSD-style compatible with MIT distribution"',
    },
    {
        "pattern": r"low-end and Android Go phones run 32-bit Android",
        "file_hint": "4_System_Architecture.md",
        "claim": "Target rural clinic devices include 32-bit only Android Go phones requiring armeabi-v7a support",
        "code": "android/app/build.gradle.kts:45",
        "test": "TC-OPS-03 (16 KB alignment & ABI packaging verified)",
        "proven": False,
        "impact": "Cost / Hardware",
        "spike_cmd": '/ng-spike "Target rural clinic devices include 32-bit only Android Go phones requiring armeabi-v7a"',
    },
    {
        "pattern": r"Research phones are often Wi-Fi only with no SIM",
        "file_hint": "7_API_Documentation.md",
        "claim": "Wi-Fi-only research phones lose RTC time after battery drain, causing JWT auth failures without clock_offset",
        "code": "android/sync/research/src/main/java/org/neogold/sync/ResearchSyncClient.kt:125",
        "test": "TC-SYNC-06 (synthetic clock skew)",
        "proven": False,
        "impact": "Schedule / Sync",
        "spike_cmd": '/ng-spike "Wi-Fi-only phones with dead batteries lose system time causing auth failures without clock_offset"',
    },
    {
        "pattern": r"virtual scene camera can show a bench image",
        "file_hint": "16_Agent_Quality_Prompts.md",
        "claim": "Android emulator virtual scene camera can display a bench image that vision engine accepts",
        "code": "android/feature/camera/src/main/java/org/neogold/camera/CameraPreviewAnalyzer.kt:60",
        "test": "None",
        "proven": False,
        "impact": "Testing Schedule",
        "spike_cmd": '/ng-spike "Emulator virtual scene camera can display a bench image that vision engine accepts"',
    },
]

SECTION_4_CANDIDATES = [
    {
        "id": "SEC4-01",
        "claim": "Hook input carries agent_type inside subagents, and hooks in subagent frontmatter apply only while subagent runs",
        "source": "docs/16_Agent_Quality_Prompts.md:94",
        "impact": "Testing Schedule",
        "spike_cmd": '/ng-spike "Hook input carries agent_type and frontmatter hooks are scoped to subagent execution"',
    },
    {
        "id": "SEC4-02",
        "claim": "A fresh-eyes session started from a sandbox folder outside repo sees no CLAUDE.md, git status, or filenames",
        "source": "docs/16_Agent_Quality_Prompts.md:95",
        "impact": "Testing Schedule",
        "spike_cmd": '/ng-spike "Fresh-eyes session outside repo sees no CLAUDE.md git status or filenames in initial context"',
    },
    {
        "id": "SEC4-03",
        "claim": "The emulator virtual scene camera can show a bench image that the vision engine accepts",
        "source": "docs/16_Agent_Quality_Prompts.md:96",
        "impact": "Testing Schedule",
        "spike_cmd": '/ng-spike "Emulator virtual scene camera can display a bench image that vision engine accepts"',
    },
    {
        "id": "SEC4-04",
        "claim": "DeepSec finds at least one real issue in backend/ that Bandit and Ruff miss",
        "source": "docs/16_Agent_Quality_Prompts.md:97",
        "impact": "Security",
        "spike_cmd": '/ng-spike "DeepSec discovers at least one actionable backend security vulnerability missed by Bandit and Ruff"',
    },
    {
        "id": "SEC4-05",
        "claim": "Maestro output gives per-step timing accurate enough for step budgets",
        "source": "docs/16_Agent_Quality_Prompts.md:98",
        "impact": "Testing Schedule",
        "spike_cmd": '/ng-spike "Maestro execution output provides step-level timing sufficient for latency budgets"',
    },
]


@dataclass
class AssumptionItem:
    file: str
    line: int
    tag: str
    context: str
    claim: str
    code_citation: str
    test_citation: str
    proven: bool
    impact: str
    impact_score: int
    spike_command: str


def find_known_metadata(file_path: str, line_text: str) -> Optional[Dict]:
    for entry in KNOWN_ASSUMPTIONS:
        if entry["file_hint"] in file_path and re.search(entry["pattern"], line_text, re.IGNORECASE):
            return entry
    return None


def scan_docs(repo_root: Path) -> List[AssumptionItem]:
    docs_dir = repo_root / "docs"
    items: List[AssumptionItem] = []

    pattern = re.compile(r"\[(Likely|Guessing)\]")

    for doc_file in sorted(docs_dir.glob("*.md")):
        rel_path = doc_file.relative_to(repo_root).as_posix()
        try:
            with open(doc_file, "r", encoding="utf-8") as f:
                lines = f.readlines()
        except UnicodeDecodeError:
            continue

        for idx, line in enumerate(lines, start=1):
            match = pattern.search(line)
            if not match:
                continue

            tag = match.group(1)
            # Skip definition lines and table structure headers
            if "Confidence tags:" in line:
                continue
            if line.strip().startswith("|") and ("---" in line or line.count("|") > 4 and "When" in line):
                continue

            clean_line = line.strip()
            meta = find_known_metadata(rel_path, clean_line)

            if meta:
                claim = meta["claim"]
                code_cit = meta["code"]
                test_cit = meta["test"]
                proven = meta["proven"]
                impact = meta["impact"]
                spike_cmd = meta["spike_cmd"]
            else:
                claim = clean_line
                code_cit = "None directly cited"
                test_cit = "Unverified"
                proven = False
                impact = "Process" if tag == "Likely" else "Schedule"
                clean_claim = re.sub(r"\[(Likely|Guessing)\]", "", claim).strip()
                spike_cmd = f'/ng-spike "{clean_claim[:60]}"'

            score = IMPACT_WEIGHTS.get(impact, 10)
            if not proven:
                score += 15

            items.append(
                AssumptionItem(
                    file=rel_path,
                    line=idx,
                    tag=tag,
                    context=clean_line,
                    claim=claim,
                    code_citation=code_cit,
                    test_citation=test_cit,
                    proven=proven,
                    impact=impact,
                    impact_score=score,
                    spike_command=spike_cmd,
                )
            )

    items.sort(key=lambda x: (x.proven, -x.impact_score, x.file, x.line))
    return items


def generate_report(repo_root: Path, items: List[AssumptionItem], report_date: Optional[str] = None) -> Path:
    if not report_date:
        report_date = datetime.date.today().isoformat()

    reports_dir = repo_root / "reports" / "assumptions"
    reports_dir.mkdir(parents=True, exist_ok=True)
    report_file = reports_dir / f"{report_date}.md"

    unproven_items = [i for i in items if not i.proven]
    top_10 = unproven_items[:10]

    lines = [
        f"# NeoGold Unverified Assumptions Audit ({report_date})",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| Date | {report_date} |",
        f"| Total Scanned Tags | {len(items)} |",
        f"| Unproven Assumptions | {len(unproven_items)} |",
        "| Priority Ranking | Clinical > Privacy > Security > Cost > Schedule |",
        "",
        "---",
        "",
        "## 1. Top 10 Priority Unproven Claims",
        "",
        "These claims are unproven and code or release processes depend on them. They are ready to test via `/ng-spike`:",
        "",
        "| Rank | Impact | Source | Claim | Code Citation | Ready-to-Run Command |",
        "|---|---|---|---|---|---|",
    ]

    for idx, item in enumerate(top_10, start=1):
        rel_link = f"[{item.file}:{item.line}]({item.file}#L{item.line})"
        lines.append(
            f"| **{idx}** | **{item.impact}** | {rel_link} `[{item.tag}]` | {item.claim} | `{item.code_citation}` | `{item.spike_command}` |"
        )

    lines.extend([
        "",
        "---",
        "",
        "## 2. Docs 16 Section 4 Candidate Spikes",
        "",
        "These spikes are identified in `docs/16_Agent_Quality_Prompts.md` Section 4 as immediate validation targets for quality loops:",
        "",
        "| ID | Source | Impact | Claim | Ready-to-Run Command |",
        "|---|---|---|---|---|",
    ])

    for cand in SECTION_4_CANDIDATES:
        lines.append(
            f"| `{cand['id']}` | [{cand['source']}](file:///{cand['source']}) | {cand['impact']} | {cand['claim']} | `{cand['spike_cmd']}` |"
        )

    lines.extend([
        "",
        "---",
        "",
        "## 3. Comprehensive Inventory of Tagged Assumptions",
        "",
        "| Source | Tag | Status | Impact | Claim | Existing Test / Proof |",
        "|---|---|---|---|---|---|",
    ])

    for item in items:
        rel_link = f"[{item.file}:{item.line}]({item.file}#L{item.line})"
        status = "Proven" if item.proven else "Unproven"
        lines.append(
            f"| {rel_link} | `[{item.tag}]` | {status} | {item.impact} | {item.claim[:80]} | {item.test_citation} |"
        )

    with open(report_file, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    return report_file


def main():
    parser = argparse.ArgumentParser(description="Audit unverified claims in NeoGold design documents.")
    parser.add_argument("--repo-root", default=".", help="Root of neogold-android repository")
    parser.add_argument("--report", action="store_true", help="Generate reports/assumptions/YYYY-MM-DD.md")
    parser.add_argument("--json", action="store_true", help="Output results as JSON")
    parser.add_argument("--date", default=None, help="Explicit report date (YYYY-MM-DD)")

    args = parser.parse_args()
    root = Path(args.repo_root).resolve()

    items = scan_docs(root)
    unproven = [i for i in items if not i.proven]

    if args.json:
        data = {
            "total_count": len(items),
            "unproven_count": len(unproven),
            "items": [asdict(i) for i in items],
            "section_4_candidates": SECTION_4_CANDIDATES,
        }
        print(json.dumps(data, indent=2))
        return 0

    if args.report:
        report_path = generate_report(root, items, args.date)
        print(f"Report generated: {report_path.relative_to(root)}")
    else:
        print(f"Scanned {len(items)} tagged assumptions ({len(unproven)} unproven).")
        print("\nTop 5 priority unproven claims:")
        for idx, item in enumerate(unproven[:5], start=1):
            print(f" {idx}. [{item.impact}] {item.file}:{item.line} - {item.claim[:60]}")
            print(f"    Command: {item.spike_command}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
