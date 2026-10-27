"""
tools/tests/test_check_strings_banned.py

Enforces P25 / Doc 6 Section 10.2 / TC-UI-04:
Proves that the banned words string check detects and fails on banned words
in both English and Bangla.
"""

import sys
from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from check_strings import check_strings_content


def test_banned_english_word_detected():
    """Verify that using an English banned word ('safe') produces a lint violation."""
    en_clean = {"action_continue": "Continue"}
    bn_clean = {"action_continue": "চালিয়ে যান"}
    assert check_strings_content(en_clean, bn_clean) == []

    en_violation = {"action_status": "The baby is safe"}
    bn_strings = {"action_status": "শিশু ভালো আছে"}
    errors = check_strings_content(en_violation, bn_strings)

    assert len(errors) == 1
    assert "contains banned pattern" in errors[0]
    assert "safe" in errors[0]


def test_banned_bangla_word_detected():
    """Verify that using a Bangla banned word ('নিরাপদ') produces a lint violation."""
    en_strings = {"action_status": "No further action from this screen"}
    bn_violation = {"action_status": "শিশু নিরাপদ আছে"}
    errors = check_strings_content(en_strings, bn_violation)

    assert len(errors) == 1
    assert "contains banned pattern" in errors[0]
    assert "নিরাপদ" in errors[0]


def test_multiple_banned_words_detected():
    """Verify multiple banned words in both languages are caught simultaneously."""
    en_multi = {
        "text_1": "Accurate diagnosis powered by AI",
        "text_2": "Normal and healthy",
    }
    bn_multi = {
        "text_1": "রোগ নির্ণয় এবং কৃত্রিম বুদ্ধিমত্তা",
        "text_2": "সুস্থ ও স্বাভাবিক",
    }
    errors = check_strings_content(en_multi, bn_multi)
    # Should catch all banned terms
    assert len(errors) >= 4
