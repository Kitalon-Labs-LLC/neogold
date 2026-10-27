"""
Tests for .claude/hooks/bash_guard.py (P25)
"""

import json
import subprocess
import sys
from pathlib import Path
import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HOOK_PATH = REPO_ROOT / ".claude" / "hooks" / "bash_guard.py"

sys.path.insert(0, str(REPO_ROOT / ".claude" / "hooks"))
from bash_guard import verdict


@pytest.mark.parametrize(
    "cmd",
    [
        "git commit -m 'test' --no-verify",
        "git push --force origin branch",
        "git push -f origin branch",
        "git push --force-with-lease origin branch",
        "./gradlew test -x check",
        "./gradlew assembleRelease -x test",
        "./gradlew build --exclude-task test",
        "cat .env",
        "cat backend/.env",
        "head -n 5 .env",
        "grep SECRET .env",
        "type .env",
        "cat release.keystore",
        "openssl pkcs12 -in upload.jks",
    ],
)
def test_bash_guard_denies_violations(cmd):
    reason = verdict(cmd)
    assert reason is not None


@pytest.mark.parametrize(
    "cmd",
    [
        "git status",
        "git diff main...HEAD",
        "./gradlew testReleaseUnitTest",
        "pytest backend/tests",
        "python3 tools/trace.py docs",
        "bundletool build-apks --bundle=app.aab --output=out.apks",
    ],
)
def test_bash_guard_allows_safe_commands(cmd):
    assert verdict(cmd) is None


def test_hook_protocol_exit_2_on_blocked_command():
    payload = json.dumps({
        "tool_name": "Bash",
        "tool_input": {"command": "git commit -m 'bypass' --no-verify"},
    })
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True)
    assert res.returncode == 2
    assert "no-verify is strictly forbidden" in res.stderr
    data = json.loads(res.stdout)
    assert data["hookSpecificOutput"]["permissionDecision"] == "deny"


def test_hook_protocol_exit_0_on_safe_command():
    payload = json.dumps({
        "tool_name": "Bash",
        "tool_input": {"command": "git status"},
    })
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True)
    assert res.returncode == 0
