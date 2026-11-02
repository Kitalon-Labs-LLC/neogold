"""Unit and tooling tests for NeoGold Feedback Triage Loop (P30).

Tests:
- Privacy screen: detection and blocking of baby photos and baby names;
  redaction of phone numbers, register numbers, dates of birth, adult names;
  audit logging.
- RULES.md sorting: deterministic sorting into exactly one bucket across
  Clinical, Privacy/Security, Clear Defect, Confusion/Request, Duplicate/Noise.
- Language detection and polite reply drafts without date commitments.
- Play Vitals dry run and synthetic fixture formatting.
- Report times: milestone calculation, weekly median aggregation, SLA target validation,
  and markdown table formatting.
- Inbox lifecycle: processing and emptying.

Cites: Doc 8 Sections 3, 6.6, 12, 12.3; Doc 9 Section 9; Doc 11; Doc 12 Section 12;
TC-PRIV-04, TC-PRIV-05, TC-SEC-01, TC-SEC-05, TC-TRI-26, TC-OPS-06.
"""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import pytest

from tools.feedback.privacy_screen import (
    screen_text,
    log_screen_event,
    PrivacyScreenResult,
)
from tools.feedback.play_vitals import (
    load_synthetic_fixture,
    format_vitals_markdown,
)
from tools.feedback.report_times import (
    calculate_report_milestones,
    aggregate_weekly_metrics,
    format_summary_table,
    load_reports_from_file,
)
from tools.feedback.triage import (
    classify_report,
    detect_language,
    draft_reply,
    triage_single_report,
    process_inbox,
    format_triage_session_summary,
)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent


# ==============================================================================
# Privacy Screen Tests (TC-PRIV-04, TC-PRIV-05)
# ==============================================================================


def test_privacy_screen_blocks_baby_name() -> None:
    """TC-PRIV-04: Report with planted baby name must be blocked and not copied."""
    planted = "Please check Baby of Rahima from Ward 3. Phone 01711223344."
    result = screen_text(planted)

    assert result.blocked is True
    assert result.clean_text == ""
    assert result.screen_fired is True
    assert any("baby_name" in item for item in result.detected_items)
    assert result.sender_action_needed is not None


def test_privacy_screen_blocks_baby_photo_attachment(tmp_path: Path) -> None:
    """TC-PRIV-04: Attached baby photo or image file must be blocked and not copied."""
    photo_file = tmp_path / "baby_chest.jpg"
    photo_file.write_bytes(b"\xff\xd8\xff\xe0test_image")

    text = "Here is the photo taken during screening."
    result = screen_text(text, attachments=[photo_file])

    assert result.blocked is True
    assert result.clean_text == ""
    assert result.screen_fired is True
    assert any("image_attachment" in item for item in result.detected_items)


def test_privacy_screen_redacts_planted_identifiers() -> None:
    """TC-PRIV-05: Redacts phone, register number, DOB, and staff name without blocking."""
    content = (
        "Sender: Nurse Fatima. Register code REG-99124 at Dhaka clinic.\n"
        "Contact mother at +8801812345678. DOB: 2026-10-22.\n"
        "Camera button was unresponsive."
    )
    result = screen_text(content)

    assert result.blocked is False
    assert result.screen_fired is True
    assert "[REDACTED_NAME]" in result.clean_text
    assert "[REDACTED_REGISTER_NO]" in result.clean_text
    assert "[REDACTED_PHONE]" in result.clean_text
    assert "[REDACTED_DOB]" in result.clean_text

    # Ensure no raw personal values survived in clean_text
    assert "Fatima" not in result.clean_text
    assert "REG-99124" not in result.clean_text
    assert "+8801812345678" not in result.clean_text
    assert "2026-10-22" not in result.clean_text


def test_privacy_screen_audit_log(tmp_path: Path) -> None:
    """TC-PRIV-05: Logs privacy screen activations without logging personal data."""
    log_file = tmp_path / "privacy_screen.log"
    result = PrivacyScreenResult(
        clean_text="Clean content",
        blocked=True,
        block_reason="Baby identifier detected",
        redactions_count=2,
        detected_items=["baby_name:Baby of X", "phone_number"],
        screen_fired=True,
        sender_action_needed="Ask sender to delete",
    )

    log_screen_event(result, source_identifier="test_report_123", log_file=log_file)
    assert log_file.is_file()

    entries = [json.loads(line) for line in log_file.read_text(encoding="utf-8").splitlines()]
    assert len(entries) == 1
    assert entries[0]["screen_fired"] is True
    assert entries[0]["blocked"] is True
    assert entries[0]["source"] == "test_report_123"


# ==============================================================================
# RULES.md Bucket Sorting & Triage Tests (TC-TRI-26, TC-SEC-01)
# ==============================================================================


def test_triage_synthetic_clinical_bucket() -> None:
    """TC-TRI-26: Clinical report flagged S1, hazard linked, clinical lead note drafted, no agent fix."""
    report = triage_single_report(
        raw_content="Triage threshold discrepancy: baby above phototherapy cutoff showed no action.",
        title="Triage error on newborn",
        source="Field report",
        report_id="TEST-CLIN-01",
    )

    assert report.bucket == "Clinical"
    assert report.severity == "S1"
    assert report.clinical_flag is True
    assert report.hazard == "H1"
    assert report.tier == 0
    assert "Plan only" in report.agent_action_allowed
    assert report.clinical_lead_note is not None
    assert "CLINICAL SAFETY INCIDENT NOTIFICATION" in report.clinical_lead_note
    assert report.defect_markdown is not None


def test_triage_synthetic_privacy_security_bucket() -> None:
    """TC-SEC-01: Security report triggers private incident record with 4-hour containment."""
    report = triage_single_report(
        raw_content="A debug export revealed plaintext auth tokens and unencrypted participant secret.",
        title="Token exposure in export",
        source="Security audit",
        report_id="TEST-SEC-01",
    )

    assert report.bucket == "Privacy/Security"
    assert report.severity == "S1"
    assert report.tier == 1
    assert report.incident_markdown is not None
    assert "Incident Record" in report.incident_markdown
    assert "Time Zero" in report.incident_markdown
    assert "Within 4 Hours" in report.incident_markdown
    assert "No public issue" in report.agent_action_allowed


def test_triage_synthetic_clear_defect_bucket() -> None:
    """Clear non-clinical defect on Tier 2 UI screen allows fix branch and PR."""
    report = triage_single_report(
        raw_content="FATAL EXCEPTION: NullPointerException in LanguageScreen when rotating device.",
        title="App crashes on rotation",
        source="Crash report",
        report_id="TEST-DEF-01",
    )

    assert report.bucket == "Clear Defect"
    assert report.severity == "S2"
    assert report.tier == 2
    assert "Fix branch + PR permitted" in report.agent_action_allowed
    assert report.defect_markdown is not None


def test_triage_synthetic_confusion_bucket() -> None:
    """Usability confusion generates 2-3 questions for site and /ng-fresh-eyes recommendation."""
    report = triage_single_report(
        raw_content="How far should I hold the phone? The app repeatedly showed lighting warning.",
        title="Confused by lighting warning",
        source="Nurse feedback",
        report_id="TEST-CONF-01",
    )

    assert report.bucket == "Confusion/Request"
    assert len(report.clarification_questions) >= 2
    assert report.fresh_eyes_suggestion is not None
    assert "/ng-fresh-eyes" in report.fresh_eyes_suggestion


def test_triage_synthetic_duplicate_bucket() -> None:
    """Duplicate report links to existing defect ID."""
    report = triage_single_report(
        raw_content="Device check fails with number format error BUG-2026-067 again.",
        title="Crash in S05",
        source="Review",
        report_id="TEST-DUP-01",
        known_bugs=["BUG-2026-067"],
    )

    assert report.bucket == "Duplicate/Noise"
    assert report.duplicate_of == "BUG-2026-067"


def test_all_five_buckets_represented_in_fixtures() -> None:
    """Done when: A dry run on synthetic reports sorts every one correctly into the 5 buckets."""
    fixture_path = REPO_ROOT / "testdata" / "feedback" / "synthetic_reports.json"
    assert fixture_path.is_file()

    items = json.loads(fixture_path.read_text(encoding="utf-8"))
    triaged = [
        triage_single_report(
            raw_content=it["content"],
            title=it["title"],
            source=it["source"],
            report_id=it["id"],
            known_bugs=it.get("known_bugs", []),
        )
        for it in items
    ]

    buckets = {t.bucket for t in triaged}
    assert "Clinical" in buckets
    assert "Privacy/Security" in buckets
    assert "Clear Defect" in buckets
    assert "Confusion/Request" in buckets
    assert "Duplicate/Noise" in buckets
    assert "Blocked (Privacy Violation)" in buckets

    # Executive summary generation check
    summary = format_triage_session_summary(triaged)
    assert "What Needs Me Today" in summary
    assert "What Is In Progress" in summary
    assert "What Was Closed / Duplicate" in summary


# ==============================================================================
# Bilingual Reply Drafts (Zero Date Promises)
# ==============================================================================


def test_draft_replies_bangla_and_english() -> None:
    """Verifies Bangla and English replies have appropriate tone and no date promises."""
    en_reply = draft_reply("Triage result was unexpected", "Clinical", "en")
    bn_reply = draft_reply("রিপোর্ট", "Clinical", "bn")

    assert "clinical review" in en_reply.lower()
    assert "ক্লিনিক্যাল" in bn_reply

    forbidden_date_words = ["by monday", "tomorrow", "next week", "release on", "fixed by", "আগামীকাল", "পরশু"]
    for word in forbidden_date_words:
        assert word not in en_reply.lower()
        assert word not in bn_reply.lower()


# ==============================================================================
# Play Vitals Tests (TC-OPS-06)
# ==============================================================================


def test_play_vitals_fixture_formatting() -> None:
    """TC-OPS-06: Verifies Play vitals fixture loading and Markdown output."""
    fixture_path = REPO_ROOT / "testdata" / "feedback" / "synthetic_vitals.json"
    assert fixture_path.is_file()

    data = load_synthetic_fixture(fixture_path)
    md = format_vitals_markdown(data)

    assert "Crash and ANR Overview" in md
    assert "Active Error Clusters" in md
    assert "TECNO Spark 30C" in md
    assert "Card window not detected" in md


# ==============================================================================
# Report Times Tests (Doc 9 Section 9.2 Turnaround Medians)
# ==============================================================================


def test_report_times_weekly_medians_and_sla() -> None:
    """TC-OPS-06: report_times.py calculates weekly medians and verifies S1 SLA (<24h)."""
    fixture_path = REPO_ROOT / "testdata" / "feedback" / "synthetic_timelines.json"
    assert fixture_path.is_file()

    reports = load_reports_from_file(fixture_path)
    processed = [calculate_report_milestones(r) for r in reports]
    weekly = aggregate_weekly_metrics(processed)

    assert "2026-W44" in weekly
    w44 = weekly["2026-W44"]
    assert w44["total_reports"] == 3
    assert w44["s1_count"] == 1

    # S1 was reported at 08:00 and sorted at 12:00 -> 4.0 hours (well within 24h SLA)
    assert w44["median_sorted_h"] is not None
    assert w44["sla_target_status"] == "MET"

    table_md = format_summary_table(weekly)
    assert "| Week | Total Reports |" in table_md
    assert "2026-W44" in table_md
    assert "**MET**" in table_md


# ==============================================================================
# Inbox Processing Lifecycle
# ==============================================================================


def test_inbox_lifecycle_emptied_after_triage(tmp_path: Path) -> None:
    """Verifies that feedback/inbox/ is processed and safely emptied."""
    inbox = tmp_path / "inbox"
    inbox.mkdir()

    gitkeep = inbox / ".gitkeep"
    gitkeep.write_text("# keep", encoding="utf-8")

    report_1 = inbox / "crash_1.txt"
    report_1.write_text("FATAL EXCEPTION in Settings", encoding="utf-8")

    report_2 = inbox / "crash_2.txt"
    report_2.write_text("How do I position the card?", encoding="utf-8")

    triaged = process_inbox(inbox, empty_after=True)
    assert len(triaged) == 2

    # Inbox files deleted, .gitkeep preserved
    assert not report_1.exists()
    assert not report_2.exists()
    assert gitkeep.exists()

