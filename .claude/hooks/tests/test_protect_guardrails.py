"""
Tests for .claude/hooks/protect_guardrails.py (P25)
"""

import json
import subprocess
import sys
from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HOOK_PATH = REPO_ROOT / ".claude" / "hooks" / "protect_guardrails.py"

sys.path.insert(0, str(REPO_ROOT / ".claude" / "hooks"))
from protect_guardrails import verdict, is_guardrail_path


@pytest.mark.parametrize(
    "path",
    [
        ".claude/settings.json",
        ".claude/hooks/bash_guard.py",
        ".claude/agents/safety-reviewer.md",
        "quality/tiers.yaml",
        "quality/tc-baseline.json",
        "tools/check_guardrails.py",
        "tools/check_dependencies.py",
        ".github/workflows/android-ci.yml",
    ],
)
def test_guardrails_triggers_ask(path):
    assert is_guardrail_path(path) is True
    reason = verdict(path)
    assert reason is not None
    assert "requires human confirmation" in reason


@pytest.mark.parametrize(
    "path",
    [
        "android/app/src/main/AndroidManifest.xml",
        "backend/app/main.py",
        "docs/2_PRD.md",
    ],
)
def test_non_guardrails_allows(path):
    assert is_guardrail_path(path) is False
    assert verdict(path) is None


def test_hook_protocol_guardrail_returns_ask():
    payload = json.dumps({
        "tool_name": "Edit",
        "tool_input": {"file_path": ".claude/settings.json"},
    })
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True)
    assert res.returncode == 0
    data = json.loads(res.stdout)
    assert data["hookSpecificOutput"]["permissionDecision"] == "ask"
