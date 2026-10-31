"""
tools/tests/test_check_strings_stems.py

TC-UI-04 / doc 6 Section 10.2: the banned words are found in their inflected forms and in the Kotlin UI literals that have
not yet moved to strings.xml (BUG-2026-091). "Accuracy" and "diagnostic" overclaim as much as "accurate" and "diagnose".
"""

import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO_ROOT / "tools"))

from check_strings import banned_words_in_literals, check_literals_banned, check_strings_content  # noqa: E402


@pytest.mark.parametrize(
    "text",
    [
        "Accuracy: 3.2",
        "A diagnostic device",
        "The baby was diagnosed",
        "Normally fine",
        "Safely home",
        "A precision of 2",
        "Safer than before",
        "Normality restored",
        "Jaundice-free",
        "No signs of jaundice",
        "A healthier baby",
    ],
)
def test_inflected_banned_english_forms_are_found(text):
    assert check_strings_content({"k": text}, {"k": "x"}), text


@pytest.mark.parametrize(
    "text",
    [
        "Safety questions",
        "Trained health workers",
        "Colour difference: 3.2",
        "Intended use: NeoGold suggests a next step; the health worker decides.",
        "Abnormal",  # not the banned claim; the word-start boundary keeps the list from over-matching
    ],
)
def test_words_that_are_not_the_banned_claims_pass(text):
    assert check_strings_content({"k": text}, {"k": "x"}) == [], text


def test_a_banned_word_in_a_kotlin_ui_literal_is_found_with_its_line():
    source = 'Column {\n    Text(text = "The baby is safe")\n    Text(text = "Next step")\n}\n'

    errors = banned_words_in_literals(source, "S99.kt")

    assert len(errors) == 1
    assert errors[0].startswith("S99.kt:2:")
    assert "safe" in errors[0]


def test_a_clean_kotlin_ui_literal_passes():
    assert banned_words_in_literals('Text(text = "Next step")', "S99.kt") == []


def test_the_repository_has_no_banned_word_in_any_kotlin_ui_literal():
    assert check_literals_banned(REPO_ROOT) == []


def test_unwell_in_bangla_is_not_taken_for_the_banned_word_healthy():
    assert check_strings_content({"k": "x"}, {"k": "শিশু অসুস্থ"}) == []
    assert check_strings_content({"k": "x"}, {"k": "শিশু সুস্থ"})
    assert check_strings_content({"k": "x"}, {"k": "চিন্তার কিছু নেই"})
