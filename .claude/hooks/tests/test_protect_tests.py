"""
Tests for .claude/hooks/protect_tests.py (P25)
"""

import json
import subprocess
import sys
from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HOOK_PATH = REPO_ROOT / ".claude" / "hooks" / "protect_tests.py"

sys.path.insert(0, str(REPO_ROOT / ".claude" / "hooks"))
from protect_tests import evaluate_edit_or_write, evaluate_bash_command


def test_deny_skip_marker_in_tier0_test():
    file_path = "android/engine/triage/src/test/kotlin/TriageTest.kt"
    decision, reason = evaluate_edit_or_write(file_path, "@Ignore\nclass TriageTest")
    assert decision == "deny"
    assert "strictly forbidden" in reason


def test_deny_skip_marker_in_tier1_test():
    file_path = "backend/tests/test_device_auth.py"
    decision, reason = evaluate_edit_or_write(file_path, "@pytest.mark.skip\ndef test_auth(): pass")
    assert decision == "deny"
    assert "strictly forbidden" in reason


def test_ask_skip_marker_in_tier2_test():
    file_path = "android/feature/settings/src/test/kotlin/SettingsTest.kt"
    decision, reason = evaluate_edit_or_write(file_path, "@Ignore\nclass SettingsTest")
    assert decision == "ask"
    assert "requires human confirmation" in reason


def test_deny_bash_rm_of_tier0_test():
    decision, reason = evaluate_bash_command("rm android/engine/triage/src/test/kotlin/TriageTest.kt")
    assert decision == "deny"
    assert "Deleting or moving Tier 0/1 test file" in reason

    decision, reason = evaluate_bash_command("git rm android/core/security/src/test/CryptoTest.kt")
    assert decision == "deny"


def test_allow_bash_rm_of_non_tier0_file():
    decision, reason = evaluate_bash_command("rm temp.txt")
    assert decision == "allow"
    assert reason is None


def test_hook_protocol_tier0_denial():
    payload = json.dumps({
        "tool_name": "Edit",
        "tool_input": {
            "file_path": "android/engine/triage/src/test/kotlin/TriageTest.kt",
            "content": "@Ignore\nfun test() {}",
        },
    })
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True)
    assert res.returncode == 2
    assert "strictly forbidden" in res.stderr


def test_hook_protocol_tier2_ask():
    payload = json.dumps({
        "tool_name": "Edit",
        "tool_input": {
            "file_path": "android/feature/settings/src/test/kotlin/SettingsTest.kt",
            "content": "@Ignore\nfun test() {}",
        },
    })
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True)
    assert res.returncode == 0
    data = json.loads(res.stdout)
    assert data["hookSpecificOutput"]["permissionDecision"] == "ask"
