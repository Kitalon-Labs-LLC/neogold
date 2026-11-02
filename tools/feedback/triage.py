#!/usr/bin/env python3
"""tools/feedback/triage.py

Automated feedback triage loop engine for NeoGold (P30).
Ingests feedback from all supported sources, runs mandatory privacy screening,
applies deterministic RULES.md bucket sorting, drafts reporter replies, and generates
actionable artifacts for human review.

Doc 8 Sections 6.6, 10, 12, 12.3; Doc 9 Section 9; Doc 11; Doc 12 Section 12.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
from typing import Any

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from tools.feedback.privacy_screen import screen_text, log_screen_event, PrivacyScreenResult

# Hazard definitions from doc 8 Section 14
CLINICAL_KEYWORDS = [
    "triage", "threshold", "referral", "treatment", "phototherapy",
    "false negative", "missed", "danger signs", "jaundice", "blood test",
    "gestational age", "bilirubin", "mg/dl", "h1", "h2", "h3", "h4", "h5",
    "h6", "h7", "h8", "h9", "h10", "h11", "recheck zone", "clinical",
    "action differed", "showed no action", "urgent referral"
]

SECURITY_PRIVACY_KEYWORDS = [
    "security", "privacy", "breach", "leak", "secret", "token", "credential",
    "argon2", "keystore", "sqlcipher", "unencrypted", "cve", "vulnerability",
    "exposure", "incident", "t1", "t2", "t3", "t7", "t8", "t9", "t10", "t12",
    "pin bypass", "auth bypass", "private key"
]

CONFUSION_KEYWORDS = [
    "how do i", "how to", "confused", "unclear", "lighting warning", "where to place",
    "can we add", "feature request", "why did it say", "how far", "distance",
    "kivabe", "bujhte parini", "ki korte hobe"
]

# Bangla script detection
BANGLA_CHAR_PATTERN = re.compile(r"[\u0980-\u09FF]")


@dataclass
class TriagedReport:
    report_id: str
    source: str
    original_title: str
    bucket: str  # Clinical, Privacy/Security, Clear Defect, Confusion/Request, Duplicate/Noise
    severity: str | None  # S1, S2, S3, S4
    clinical_flag: bool
    hazard: str | None
    tier: int | None
    screen_result: PrivacyScreenResult
    defect_markdown: str | None = None
    incident_markdown: str | None = None
    clinical_lead_note: str | None = None
    clarification_questions: list[str] = field(default_factory=list)
    fresh_eyes_suggestion: str | None = None
    duplicate_of: str | None = None
    reply_draft: str | None = None
    agent_action_allowed: str = "None"  # "Fix branch + PR", "Plan only", "Human only"


def detect_language(text: str) -> str:
    """Detects whether the report is predominantly Bangla or English."""
    if BANGLA_CHAR_PATTERN.search(text):
        return "bn"
    return "en"


def classify_report(
    clean_text: str,
    title: str,
    source: str,
    known_bugs: list[str] | None = None,
) -> tuple[str, str | None, bool, str | None, int | None, str | None]:
    """Classifies clean text into exactly one bucket per feedback/RULES.md.

    Returns: (bucket, severity, clinical_flag, hazard, tier, duplicate_of)
    """
    text_lower = (title + " " + clean_text).lower()

    # 1. Clinical check (highest precedence)
    for kw in CLINICAL_KEYWORDS:
        if kw in text_lower:
            hazard = "H1"  # Default hazard: H1 False reassurance (missed baby needing treatment)
            if "gestational" in text_lower:
                hazard = "H3"
            elif "birth time" in text_lower or "clock" in text_lower:
                hazard = "H2"
            elif "card" in text_lower or "faded" in text_lower:
                hazard = "H4"
            elif "camera" in text_lower or "qualification" in text_lower:
                hazard = "H5"
            elif "light" in text_lower or "lighting" in text_lower:
                hazard = "H6"
            elif "corrupt" in text_lower or "tampered" in text_lower or "config file" in text_lower:
                hazard = "H7"
            elif "outdated" in text_lower or "expired" in text_lower:
                hazard = "H8"
            elif "recheck" in text_lower or "notification" in text_lower:
                hazard = "H10"
            elif "misread" in text_lower or "wording" in text_lower:
                hazard = "H11"
            return ("Clinical", "S1", True, hazard, 0, None)

    # 2. Privacy or Security check
    for kw in SECURITY_PRIVACY_KEYWORDS:
        if kw in text_lower:
            return ("Privacy/Security", "S1", False, None, 1, None)

    # 3. Duplicate check
    if known_bugs:
        for bug_id in known_bugs:
            if bug_id.lower() in text_lower:
                return ("Duplicate/Noise", None, False, None, None, bug_id)

    # 4. Confusion or Feature Request
    for kw in CONFUSION_KEYWORDS:
        if kw in text_lower:
            return ("Confusion/Request", None, False, None, None, None)

    # 5. Clear defect (default fallback with stack trace or bug report structure)
    # Check affected tier
    tier = 2
    if "tools" in text_lower or "script" in text_lower or "ci" in text_lower:
        tier = 3
    elif "crypto" in text_lower or "database" in text_lower or "sync" in text_lower:
        tier = 1

    # Default severity for clear bugs
    severity = "S2"
    if "crash" in text_lower or "fatal" in text_lower or "exception" in text_lower:
        severity = "S2"
    elif "alignment" in text_lower or "spacing" in text_lower or "color" in text_lower:
        severity = "S4"
    else:
        severity = "S3"

    return ("Clear Defect", severity, False, None, tier, None)


def draft_reply(clean_text: str, bucket: str, lang: str) -> str:
    """Drafts polite reply in reporter's language without any date commitments."""
    if lang == "bn":
        if bucket == "Clinical":
            return (
                "নমস্কার। আপনার রিপোর্টটি আমরা অত্যন্ত গুরুত্বের সাথে গ্রহণ করেছি। "
                "আমাদের ক্লিনিক্যাল এবং টেকনিক্যাল টিম বিষয়টি বিস্তারিত পরীক্ষা করছে। "
                "প্রয়োজনে স্বাস্থ্যকর্মীর ক্লিনিক্যাল নির্দেশিকা অনুসরণ করার অনুরোধ করা হচ্ছে।"
            )
        elif bucket == "Privacy/Security":
            return (
                "নমস্কার। বিষয়টি আমাদের জানানোর জন্য ধন্যবাদ। "
                "আমাদের সিকিউরিটি টিম রিপোর্টটি পর্যালোচনা করছে এবং প্রয়োজনীয় সুরক্ষামূলক পদক্ষেপ গ্রহণ করছে।"
            )
        elif bucket == "Confusion/Request":
            return (
                "নমস্কার। আপনার মতামত ও প্রশ্নের জন্য ধন্যবাদ। "
                "আমরা আপনার অভিজ্ঞতার বিষয়টি খতিয়ে দেখছি এবং আমাদের ফিল্ড সমন্বয়কের মাধ্যমে প্রয়োজনীয় সহায়তা প্রদান করা হবে।"
            )
        else:
            return (
                "নমস্কার। সমস্যার বিবরণটি আমাদের কাছে সফলভাবে পৌঁছেছে। "
                "আমাদের ডেভেলপমেন্ট টিম বিষয়টি তদন্ত করছে এবং পরবর্তী আপডেটে এর সমাধান অন্তর্ভুক্ত করা হবে।"
            )
    else:
        if bucket == "Clinical":
            return (
                "Thank you for this report. We have flagged this report for immediate clinical review "
                "by our clinical and technical leads. Please continue to adhere strictly to your primary clinical "
                "judgment and standard newborn care protocols."
            )
        elif bucket == "Privacy/Security":
            return (
                "Thank you for contacting us. We have received your security report and our team is "
                "actively reviewing it per our security incident response procedures."
            )
        elif bucket == "Confusion/Request":
            return (
                "Thank you for your feedback. We appreciate you sharing your experience. "
                "Our team will follow up via your study site coordinator with guidance on this screen."
            )
        else:
            return (
                "Thank you for submitting this report. We have cataloged the issue and our technical team "
                "is investigating the steps to reproduce for an upcoming release."
            )


def triage_single_report(
    raw_content: str,
    title: str,
    source: str,
    report_id: str,
    attachments: list[str | Path] | None = None,
    known_bugs: list[str] | None = None,
) -> TriagedReport:
    """Performs end-to-end triage on a single report."""
    # Step 1: Mandatory Privacy Screen FIRST
    screen_res = screen_text(raw_content, attachments=attachments)

    if screen_res.blocked:
        # DO NOT COPY. Do not file issues. Alert user.
        return TriagedReport(
            report_id=report_id,
            source=source,
            original_title=title,
            bucket="Blocked (Privacy Violation)",
            severity=None,
            clinical_flag=False,
            hazard=None,
            tier=None,
            screen_result=screen_res,
            agent_action_allowed="BLOCKED: Baby identifier/photo detected. Do not copy.",
        )

    # Step 2: Classify into exactly one bucket
    bucket, severity, clinical, hazard, tier, duplicate_of = classify_report(
        clean_text=screen_res.clean_text,
        title=title,
        source=source,
        known_bugs=known_bugs,
    )

    lang = detect_language(raw_content)
    reply = draft_reply(screen_res.clean_text, bucket, lang)

    today_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    defect_md = None
    incident_md = None
    clinical_note = None
    questions: list[str] = []
    fresh_eyes = None
    action_allowed = "Human review only"

    if bucket == "Clinical":
        action_allowed = "Plan only (Tier 0 clinical rules require Clinical Lead approval)"
        clinical_note = (
            f"CLINICAL SAFETY INCIDENT NOTIFICATION (doc 8 Section 12.3)\n"
            f"Date: {today_str}\n"
            f"Report ID: {report_id}\n"
            f"Hazard: {hazard}\n"
            f"Summary: Discrepancy observed affecting potential triage/referral action.\n"
            f"Reported text (redacted): {screen_res.clean_text[:300]}...\n"
            f"Action for Clinical Lead: Review whether emergency config update with triage disabled is required."
        )
        defect_md = (
            f"### BUG-2026-TBD: {title}\n\n"
            f"- Found: {today_str} | Source: {source} | Build: 1.0.0 | Device: Field\n"
            f"- Area: TRI | Severity: S1 | Clinical: Yes | Hazard: {hazard} | Priority: P1\n"
            f"- Status: Triaged | Owner: Unassigned | Test case: TC-TRI-26\n\n"
            f"**Steps**\n1. Review reported clinical discrepancy from field.\n\n"
            f"**Expected**\nTriage action matches clinical guidelines.\n\n"
            f"**Actual**\n{screen_res.clean_text}\n"
        )
    elif bucket == "Privacy/Security":
        action_allowed = "Human containment only (No public issue allowed)"
        incident_md = (
            f"# Incident Record: {report_id}\n"
            f"- Time Zero (T0): {datetime.now(timezone.utc).isoformat()}\n"
            f"- Source: {source}\n"
            f"- Status: Active Containment\n\n"
            f"## Immediate Containment Steps (Within 4 Hours per doc 8 Section 12.2):\n"
            f"1. Halt active rollout in Play Console if release APK is involved.\n"
            f"2. Rotate exposed tokens/secrets if credential breach suspected.\n"
            f"3. Check device enrollment table and revoke affected device if compromised.\n"
            f"4. Assess data impact and notify Data Protection Lead within 24 hours.\n\n"
            f"## Details (Redacted):\n{screen_res.clean_text}\n"
        )
    elif bucket == "Clear Defect":
        if tier in (2, 3):
            action_allowed = f"Fix branch + PR permitted in this session (Tier {tier} code)"
        else:
            action_allowed = f"Plan only (Tier {tier} code)"
        defect_md = (
            f"### BUG-2026-TBD: {title}\n\n"
            f"- Found: {today_str} | Source: {source} | Build: 1.0.0 | Device: Field\n"
            f"- Area: UI | Severity: {severity} | Clinical: No | Hazard: - | Priority: P2\n"
            f"- Status: Triaged | Owner: Unassigned | Test case: TC-REL-01\n\n"
            f"**Steps**\n1. Ingested from {source}.\n\n"
            f"**Actual**\n{screen_res.clean_text}\n"
        )
    elif bucket == "Confusion/Request":
        action_allowed = "Marked 'needs a conversation'"
        questions = [
            "At what distance was the phone held from the color card?",
            "What ambient lighting was present (direct sunlight, tube light, shadow)?",
            "Was the card positioned fully flat on the newborn's chest?",
        ]
        fresh_eyes = "Run `/ng-fresh-eyes Rokeya 'Card alignment and lighting instructions' --lang bn`"
    elif bucket == "Duplicate/Noise":
        action_allowed = f"Link to existing bug {duplicate_of} or close"

    return TriagedReport(
        report_id=report_id,
        source=source,
        original_title=title,
        bucket=bucket,
        severity=severity,
        clinical_flag=clinical,
        hazard=hazard,
        tier=tier,
        screen_result=screen_res,
        defect_markdown=defect_md,
        incident_markdown=incident_md,
        clinical_lead_note=clinical_note,
        clarification_questions=questions,
        fresh_eyes_suggestion=fresh_eyes,
        duplicate_of=duplicate_of,
        reply_draft=reply,
        agent_action_allowed=action_allowed,
    )


def process_inbox(inbox_dir: Path, empty_after: bool = True) -> list[TriagedReport]:
    """Reads shared crash reports from feedback/inbox/, triages them, and empties the inbox."""
    if not inbox_dir.is_dir():
        return []

    reports: list[TriagedReport] = []
    files_to_delete: list[Path] = []

    for file_path in inbox_dir.glob("*"):
        if file_path.name == ".gitkeep":
            continue
        if file_path.is_file():
            try:
                content = file_path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                content = f"[Binary or corrupted attachment: {file_path.name}]"

            # Check if this file itself is an image attachment
            attachments = [file_path] if file_path.suffix.lower() in [".jpg", ".png", ".jpeg"] else []

            report = triage_single_report(
                raw_content=content,
                title=f"Shared crash report: {file_path.name}",
                source="Shared Crash Report (feedback/inbox/)",
                report_id=file_path.stem,
                attachments=attachments,
            )
            reports.append(report)
            files_to_delete.append(file_path)

    if empty_after:
        for f in files_to_delete:
            try:
                f.unlink()
            except Exception as e:
                print(f"Warning: could not delete inbox file {f}: {e}", file=sys.stderr)

    return reports


def format_triage_session_summary(triaged: list[TriagedReport]) -> str:
    """Generates executive summary grouped by action requirement."""
    needs_me_today: list[str] = []
    in_progress: list[str] = []
    closed_or_duplicate: list[str] = []

    for t in triaged:
        if t.screen_result.blocked:
            needs_me_today.append(
                f"- **[PRIVACY VIOLATION DETECTED]** `{t.report_id}`: {t.screen_result.block_reason}\n"
                f"  *Action:* {t.screen_result.sender_action_needed}"
            )
        elif t.bucket == "Clinical":
            needs_me_today.append(
                f"- **[CLINICAL ALERT - S1]** `{t.report_id}` (Hazard: {t.hazard})\n"
                f"  *Summary:* {t.original_title}\n"
                f"  *Action:* Review drafted Clinical Lead note. Do not fix with agent."
            )
        elif t.bucket == "Privacy/Security":
            needs_me_today.append(
                f"- **[PRIVACY/SECURITY INCIDENT]** `{t.report_id}`\n"
                f"  *Action:* Review private incident record & execute 4h containment steps."
            )
        elif t.bucket == "Confusion/Request":
            needs_me_today.append(
                f"- **[SITE CONVERSATION NEEDED]** `{t.report_id}`: {t.original_title}\n"
                f"  *Questions for site:* {'; '.join(t.clarification_questions)}\n"
                f"  *Suggestion:* {t.fresh_eyes_suggestion}"
            )
        elif t.bucket == "Clear Defect":
            in_progress.append(
                f"- **[DEFECT {t.severity}]** `{t.report_id}`: {t.original_title} (Tier {t.tier})\n"
                f"  *Permissions:* {t.agent_action_allowed}"
            )
        elif t.bucket == "Duplicate/Noise":
            closed_or_duplicate.append(
                f"- **[DUPLICATE/NOISE]** `{t.report_id}`: linked to `{t.duplicate_of or 'closed'}`"
            )

    lines = [
        "# NeoGold Feedback Triage Summary",
        f"- Generated: {datetime.now(timezone.utc).isoformat()}",
        f"- Total Reports Triaged: {len(triaged)}",
        "",
        "## 1. What Needs Me Today",
    ]
    lines.extend(needs_me_today or ["*None.*"])

    lines.append("")
    lines.append("## 2. What Is In Progress")
    lines.extend(in_progress or ["*None.*"])

    lines.append("")
    lines.append("## 3. What Was Closed / Duplicate")
    lines.extend(closed_or_duplicate or ["*None.*"])

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="NeoGold Feedback Triage Loop.")
    parser.add_argument("--inbox", default="feedback/inbox", help="Path to feedback inbox.")
    parser.add_argument("--synthetic-reports", help="JSON file with list of synthetic reports.")
    parser.add_argument("--output-dir", default="feedback/triaged", help="Output directory.")
    parser.add_argument("--no-empty", action="store_true", help="Do not empty inbox after triage.")

    args = parser.parse_args()

    triaged: list[TriagedReport] = []

    # Ingest synthetic reports if provided
    if args.synthetic_reports:
        syn_path = Path(args.synthetic_reports)
        if syn_path.is_file():
            data = json.loads(syn_path.read_text(encoding="utf-8"))
            for item in data:
                report = triage_single_report(
                    raw_content=item.get("content", ""),
                    title=item.get("title", "Synthetic report"),
                    source=item.get("source", "Synthetic Test"),
                    report_id=item.get("id", "SYN-001"),
                    known_bugs=item.get("known_bugs", []),
                )
                triaged.append(report)

    # Ingest inbox
    inbox_p = Path(args.inbox)
    if inbox_p.is_dir():
        inbox_reports = process_inbox(inbox_p, empty_after=not args.no_empty)
        triaged.extend(inbox_reports)

    summary = format_triage_session_summary(triaged)
    print(summary)

    out_dir = Path(args.output_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "summary.md").write_text(summary, encoding="utf-8")

    return 0


if __name__ == "__main__":
    sys.exit(main())
