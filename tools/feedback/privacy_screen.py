#!/usr/bin/env python3
"""tools/feedback/privacy_screen.py

Mandatory pre-ingestion privacy screen for all feedback, crash reports, and issues.
Runs BEFORE anything is written to disk, git, or public issue trackers.

Doc 8 Sections 3, 6.6, 12; PRD FR-23, FR-34; CLAUDE.md privacy non-negotiables.

Rules:
- Redacts or drops: names, phone numbers, register numbers, dates of birth, images.
- If a report contains a baby's photo or a baby's name: DO NOT COPY IT.
  Halt copying, alert the operator so they can ask the sender to delete it,
  and log that the screen fired.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
from typing import Any

# Regex patterns for identifiers
PHONE_PATTERN = re.compile(
    r"(?:\+?880\s?1[3-9]\d{2}[-\s]?\d{6}|01[3-9]\d{2}[-\s]?\d{6}|\b(?:\+?\d{1,3}[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b)",
    re.IGNORECASE,
)

REGISTER_NO_PATTERN = re.compile(
    r"\b(?:REG|IPD|OPD|MRN|NBS|PID|UHID|HOSP|ADM)[-_#:\s]*([A-Za-z0-9]{3,12})\b",
    re.IGNORECASE,
)

DOB_PATTERN = re.compile(
    r"\b(?:DOB|Date of birth|Birth date|Born|D\.O\.B\.)[:\s]+(\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}|\d{1,2}\s+(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)[,\s]+\d{2,4})\b",
    re.IGNORECASE,
)

# Stopwords that follow clinical descriptors (not baby names)
_CLINICAL_NON_NAMES = (
    r"above|below|at|with|was|is|has|had|in|on|from|to|by|for|and|or|under|over|"
    r"screened|screen|screening|examined|born|photo|image|weight|age|risk|care|chest|skin|"
    r"color|colour|hours|days|weeks|months|who|whose|which|that|patient|jaundice|bilirubin|"
    r"treated|arrived|received|admitted|discharged|name|names|boy|girl|details|info|record|data|entry"
)

# Baby name markers: if found, this is a baby identifier and must NOT be copied
BABY_NAME_PATTERN = re.compile(
    rf"\b(?:Baby\s+of\s+[A-Za-z]+|B/O\s+[A-Za-z]+|Patient\s+Name[:\s]+[A-Za-z\s]+|(?:Baby|Infant|Neonate|Child)\s+(?!{_CLINICAL_NON_NAMES}\b)[A-Z][a-z]+)\b",
    re.IGNORECASE,
)

# Adult names or staff signatures to redact
ADULT_NAME_LABEL_PATTERN = re.compile(
    r"\b(?:Name|Reporter|Nurse|Worker|Doctor|Dr\.|Mother|Father|Guardian|Sender)[:\s]+([A-Z][a-z]+(?:\s+[A-Z][a-z]+){1,3})\b"
)

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".heic", ".bmp", ".gif", ".tiff"}
IMAGE_KEYWORDS = re.compile(
    r"(?:attached\s+photo|baby['’]?s\s+photo|newborn\s+photo|patient\s+photo|photo\s+of\s+baby|screening\s+image|camera\s+capture\s+photo)",
    re.IGNORECASE,
)


@dataclass
class PrivacyScreenResult:
    clean_text: str
    blocked: bool = False
    block_reason: str | None = None
    redactions_count: int = 0
    detected_items: list[str] = field(default_factory=list)
    screen_fired: bool = False
    sender_action_needed: str | None = None
    original_length: int = 0
    clean_length: int = 0


def screen_text(text: str, attachments: list[str | Path] | None = None) -> PrivacyScreenResult:
    """Screens text and attachments for personal identifiers and baby imagery/names.

    Returns PrivacyScreenResult. If blocked is True, clean_text is empty and the caller
    must NEVER copy or persist the original report.
    """
    if not text:
        return PrivacyScreenResult(clean_text="")

    detected: list[str] = []
    redactions = 0
    screen_fired = False

    # 1. Check for image attachments or image indicators
    has_image = False
    if attachments:
        for att in attachments:
            p = Path(att)
            if p.suffix.lower() in IMAGE_EXTENSIONS:
                has_image = True
                detected.append(f"image_attachment:{p.name}")

    if IMAGE_KEYWORDS.search(text):
        has_image = True
        detected.append("image_mention:baby_photo")

    if has_image:
        screen_fired = True
        return PrivacyScreenResult(
            clean_text="",
            blocked=True,
            block_reason="Report contains or references a baby's photo or raw image attachment.",
            redactions_count=0,
            detected_items=detected,
            screen_fired=True,
            sender_action_needed="Ask sender to delete photo from their device/chat and re-submit text-only without newborn images.",
            original_length=len(text),
            clean_length=0,
        )

    # 2. Check for baby's name (hard stop: do not copy)
    baby_name_match = BABY_NAME_PATTERN.search(text)
    if baby_name_match:
        detected.append(f"baby_name:{baby_name_match.group(0)}")
        screen_fired = True
        return PrivacyScreenResult(
            clean_text="",
            blocked=True,
            block_reason=f"Report contains a baby's or patient's name ('{baby_name_match.group(0)}').",
            redactions_count=0,
            detected_items=detected,
            screen_fired=True,
            sender_action_needed="Ask sender to delete report from chat/email and re-submit using only synthetic codes or doc 11 format.",
            original_length=len(text),
            clean_length=0,
        )

    # 3. Scrub and redact other identifiers
    scrubbed = text

    # Phone numbers
    def _redact_phone(match: re.Match[str]) -> str:
        nonlocal redactions
        redactions += 1
        return "[REDACTED_PHONE]"

    if PHONE_PATTERN.search(scrubbed):
        detected.append("phone_number")
        scrubbed = PHONE_PATTERN.sub(_redact_phone, scrubbed)

    # Register numbers
    def _redact_reg(match: re.Match[str]) -> str:
        nonlocal redactions
        redactions += 1
        return "[REDACTED_REGISTER_NO]"

    if REGISTER_NO_PATTERN.search(scrubbed):
        detected.append("register_number")
        scrubbed = REGISTER_NO_PATTERN.sub(_redact_reg, scrubbed)

    # Dates of birth
    def _redact_dob(match: re.Match[str]) -> str:
        nonlocal redactions
        redactions += 1
        return "[REDACTED_DOB]"

    if DOB_PATTERN.search(scrubbed):
        detected.append("date_of_birth")
        scrubbed = DOB_PATTERN.sub(_redact_dob, scrubbed)

    # Labeled adult names
    def _redact_name(match: re.Match[str]) -> str:
        nonlocal redactions
        redactions += 1
        prefix = match.group(0).split(":")[0] + ": " if ":" in match.group(0) else ""
        return f"{prefix}[REDACTED_NAME]"

    if ADULT_NAME_LABEL_PATTERN.search(scrubbed):
        detected.append("adult_name")
        scrubbed = ADULT_NAME_LABEL_PATTERN.sub(_redact_name, scrubbed)

    if redactions > 0 or len(detected) > 0:
        screen_fired = True

    return PrivacyScreenResult(
        clean_text=scrubbed,
        blocked=False,
        block_reason=None,
        redactions_count=redactions,
        detected_items=detected,
        screen_fired=screen_fired,
        sender_action_needed=None,
        original_length=len(text),
        clean_length=len(scrubbed),
    )


def log_screen_event(
    result: PrivacyScreenResult,
    source_identifier: str,
    log_file: Path | None = None,
) -> None:
    """Logs the fact that the privacy screen fired without logging the sensitive data itself."""
    if not result.screen_fired:
        return

    if log_file is None:
        log_file = Path("feedback") / "privacy_screen.log"

    log_file.parent.mkdir(parents=True, exist_ok=True)
    entry = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "source": source_identifier,
        "screen_fired": True,
        "blocked": result.blocked,
        "block_reason": result.block_reason,
        "detected_categories": result.detected_items,
        "redactions_count": result.redactions_count,
        "sender_action_needed": result.sender_action_needed,
    }

    with open(log_file, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry) + "\n")


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 tools/feedback/privacy_screen.py <report_file>", file=sys.stderr)
        return 1

    target = Path(sys.argv[1])
    if not target.is_file():
        print(f"Error: file not found: {target}", file=sys.stderr)
        return 1

    content = target.read_text(encoding="utf-8")
    result = screen_text(content)

    if result.blocked:
        print(f"PRIVACY SCREEN BLOCKED REPORT: {result.block_reason}", file=sys.stderr)
        if result.sender_action_needed:
            print(f"Action needed: {result.sender_action_needed}", file=sys.stderr)
        log_screen_event(result, source_identifier=str(target))
        return 2

    if result.screen_fired:
        print(f"Privacy screen redacted {result.redactions_count} items: {result.detected_items}", file=sys.stderr)
        log_screen_event(result, source_identifier=str(target))

    print(result.clean_text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
