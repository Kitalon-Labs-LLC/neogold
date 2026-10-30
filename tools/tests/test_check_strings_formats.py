"""Tooling tests for the format-argument check in tools/check_strings.py (BUG-2026-067, TC-UI-04)."""

import importlib.util
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
spec = importlib.util.spec_from_file_location("check_strings", REPO / "tools" / "check_strings.py")
check_strings = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_strings)

FORMATS = {
    "s05_ram": check_strings.placeholder_conversions("Memory: %d MB"),
    "s05_camera_level": check_strings.placeholder_conversions("Camera level: %s"),
    "two": check_strings.placeholder_conversions("%1$s took %2$d seconds"),
}


def errors(source: str) -> list[str]:
    return check_strings.check_format_arguments_in_source(source, FORMATS, "S.kt")


def test_placeholder_conversions_by_position():
    assert check_strings.placeholder_conversions("Memory: %d MB") == {0: "d"}
    assert check_strings.placeholder_conversions("%1$s took %2$d seconds") == {0: "s", 1: "d"}
    assert check_strings.placeholder_conversions("100%% of %s") == {0: "s"}
    assert check_strings.placeholder_conversions("no placeholders") == {}


def test_the_call_that_crashed_s05_is_found():
    source = 'Text(text = stringResource(R.string.s05_ram, ramText), style = x)'

    found = errors(source)

    assert len(found) == 1
    assert "s05_ram" in found[0] and "%d" in found[0]


def test_a_formatted_string_given_to_a_number_placeholder_is_found():
    assert errors("stringResource(R.string.s05_ram, ClinicalNumberFormatter.formatInteger(ram))")
    assert errors('stringResource(R.string.s05_ram, "4096")')
    assert errors("stringResource(R.string.s05_ram, ram.toString())")
    assert errors("stringResource(\n    R.string.s05_ram,\n    formatInteger(ram)\n)")


def test_a_number_given_to_a_number_placeholder_is_fine():
    assert errors("stringResource(R.string.s05_ram, report.capabilities.ramMb)") == []
    assert errors("stringResource(R.string.s05_ram, taken + 1)") == []
    assert errors("stringResource(R.string.s05_ram, count.coerceIn(1, 3))") == []


def test_anything_goes_into_a_string_placeholder():
    assert errors("stringResource(R.string.s05_camera_level, level.name)") == []
    assert errors("stringResource(R.string.s05_camera_level, ClinicalNumberFormatter.formatInteger(1))") == []


def test_positional_placeholders_are_matched_to_their_argument():
    assert errors("stringResource(R.string.two, name, seconds)") == []
    assert errors("stringResource(R.string.two, name, secondsText)")


def test_getstring_and_a_qualified_resource_class_are_read_too():
    assert errors("context.getString(R.string.s05_ram, ramText)")
    assert errors("stringResource(com.kitalonlabs.neogold.core.designsystem.R.string.s05_ram, ramText)")


def test_a_string_without_arguments_or_an_unknown_key_is_ignored():
    assert errors("stringResource(R.string.s05_ram)") == []
    assert errors("stringResource(R.string.not_a_format_string, ramText)") == []


def test_the_repository_passes_its_own_check():
    res_dirs = check_strings.find_res_dirs(REPO)

    assert check_strings.check_format_arguments(REPO, res_dirs) == []
