"""Tooling tests for the Kotlin hardcoded-literal ratchet in tools/check_strings.py (BUG-2026-029, TC-A11Y-01)."""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
TOOL = REPO / "tools" / "check_strings.py"
spec = importlib.util.spec_from_file_location("check_strings", TOOL)
check_strings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_strings)


def count(source: str) -> int:
    return check_strings.count_literals_in_source(source)


def test_compose_text_literal_is_counted():
    assert count('Text("Save to log")') == 1
    assert count('Text(text = "Save to log")') == 1


def test_named_ui_arguments_are_counted():
    assert count('Icon(imageVector = x, contentDescription = "Back")') == 1
    assert count('Foo(title = "Hello", label = "World")') == 2


def test_notification_setters_are_counted():
    assert count('builder.setContentTitle("Recheck due").setContentText("Open the app")') == 2


def test_string_resources_are_not_counted():
    assert count('Text(stringResource(R.string.save))') == 0
    assert count('Text(text = stringResource(R.string.save))') == 0
    assert count('Icon(contentDescription = stringResource(R.string.back))') == 0


def test_bangla_literal_is_counted():
    assert count('Text("সংরক্ষণ করুন")') == 1


def test_literals_without_letters_are_not_counted():
    # Symbols, digits and pure interpolation are not translatable text.
    assert count('Text("")') == 0
    assert count('Text("•")') == 0
    assert count('Text("12")') == 0
    assert count('Text("${value}")') == 0
    assert count('Text("$value")') == 0


def test_interpolation_with_words_is_counted():
    assert count('Text("${count} items")') == 1
    assert count('Text("Age: $hours h")') == 1


def test_comments_are_ignored():
    assert count('// Text("Save to log")') == 0
    assert count('/* Text("Save to log") */') == 0
    assert count(' * Example: Text("Save to log")') == 0


def test_escaped_quote_does_not_end_the_literal_early():
    assert count(r'Text("Say \"hello\" now")') == 1


def test_ratchet_passes_when_counts_equal_baseline():
    assert check_strings.check_literal_ratchet({"a.kt": 3}, {"a.kt": 3}) == []


def test_ratchet_fails_when_a_file_gains_a_literal():
    problems = check_strings.check_literal_ratchet({"a.kt": 4}, {"a.kt": 3})
    assert len(problems) == 1 and "a.kt" in problems[0] and "4" in problems[0] and "3" in problems[0]


def test_ratchet_fails_for_a_new_file_with_a_literal():
    problems = check_strings.check_literal_ratchet({"new.kt": 1}, {})
    assert len(problems) == 1 and "new.kt" in problems[0]


def test_ratchet_fails_when_baseline_is_stale_after_an_improvement():
    # The baseline must tighten, otherwise the freed slack could hide a later regression.
    problems = check_strings.check_literal_ratchet({"a.kt": 1}, {"a.kt": 3})
    assert len(problems) == 1 and "--update-baseline" in problems[0]


def test_ratchet_fails_for_a_baseline_entry_whose_file_is_gone():
    problems = check_strings.check_literal_ratchet({}, {"gone.kt": 2})
    assert len(problems) == 1 and "gone.kt" in problems[0]


def test_only_main_source_sets_are_scanned(tmp_path):
    for rel in ("app/src/main/A.kt", "app/src/test/B.kt", "app/src/debug/C.kt", "app/build/gen/D.kt"):
        path = tmp_path / "android" / rel
        path.parent.mkdir(parents=True)
        path.write_text('Text("Hello")\n', encoding="utf-8")
    counts = check_strings.scan_kotlin_literals(tmp_path)
    assert counts == {"android/app/src/main/A.kt": 1}


def test_repository_matches_its_committed_baseline():
    result = subprocess.run([sys.executable, str(TOOL)], cwd=REPO, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def test_baseline_file_is_sorted_and_has_no_zero_entries():
    data = json.loads((REPO / "tools" / "strings_baseline.json").read_text(encoding="utf-8"))
    files = data["files"]
    assert list(files) == sorted(files)
    assert all(isinstance(n, int) and n > 0 for n in files.values())
