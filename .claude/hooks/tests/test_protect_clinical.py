"""
Tests for .claude/hooks/protect_clinical.py (P25)
"""

import json
import subprocess
import sys
from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HOOK_PATH = REPO_ROOT / ".claude" / "hooks" / "protect_clinical.py"

sys.path.insert(0, str(REPO_ROOT / ".claude" / "hooks"))
from protect_clinical import verdict, DENIAL_MESSAGE, is_protected


@pytest.mark.parametrize(
    "path",
    [
        "config/triage-config.json",
        "CLINICAL_SIGNOFF.md",
        "config/trust-list.json",
        "config/model.json",
        "android/app/src/main/assets/model.json",
        "android/core/designsystem/src/main/res/values/strings.xml",
        "android/core/designsystem/src/main/res/values-bn/strings.xml",
        "d:/Project/neogold-android/config/triage-config.json",
        "./config/triage-config.json",
    ],
)
def test_protect_clinical_denies_clinical_paths(path):
    assert is_protected(path) is True
    assert verdict(path) == DENIAL_MESSAGE


@pytest.mark.parametrize(
    "path",
    [
        "android/app/src/main/java/com/kitalonlabs/neogold/MainActivity.kt",
        "testdata/triage/config.synthetic.json",
        "docs/2_PRD.md",
        "tools/check_strings.py",
    ],
)
def test_protect_clinical_allows_non_clinical_paths(path):
    assert is_protected(path) is False
    assert verdict(path) is None


def test_hook_protocol_exit_2_on_denial():
    payload = json.dumps({
        "tool_name": "Edit",
        "tool_input": {"file_path": "config/triage-config.json"},
    })
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True)
    assert res.returncode == 2
    assert "Clinical values change only with the clinical lead's signed record (PRD 9b)." in res.stderr
    # stdout should contain hookSpecificOutput
    data = json.loads(res.stdout)
    assert data["hookSpecificOutput"]["permissionDecision"] == "deny"


def test_hook_protocol_exit_0_on_allow():
    payload = json.dumps({
        "tool_name": "Edit",
        "tool_input": {"file_path": "docs/2_PRD.md"},
    })
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True)
    assert res.returncode == 0


def test_hook_fails_closed_on_bad_input():
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input="invalid json", capture_output=True, text=True)
    assert res.returncode == 2
