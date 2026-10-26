"""
tools/tests/test_check_guardrails.py

Enforces P25:
Tests that check_guardrails verifies every row in the CLAUDE.md Must not trade off table
and catches disappearing or broken enforcement links.
"""

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from check_guardrails import parse_claude_table, verify_enforcement_row


def test_real_claude_table_passes():
    claude_md = REPO_ROOT / "CLAUDE.md"
    rows = parse_claude_table(claude_md)
    assert len(rows) == 20

    for row in rows:
        errors = verify_enforcement_row(REPO_ROOT, row)
        assert errors == [], f"Row failed verification: {row['rule']} -> {errors}"


def test_broken_enforcement_link_fails(tmp_path):
    fake_claude = tmp_path / "CLAUDE.md"
    fake_claude.write_text(
        """
| Rule | Why | Enforced by | Runs at |
|---|---|---|---|
| Rule 1 | Why 1 | non_existent_tool.py | PR |
""",
        encoding="utf-8",
    )
    rows = parse_claude_table(fake_claude)
    assert len(rows) == 1
    errors = verify_enforcement_row(REPO_ROOT, rows[0])
    assert len(errors) == 1
    assert "non_existent_tool.py" in errors[0]
