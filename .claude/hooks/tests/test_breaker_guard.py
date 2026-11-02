"""Tests for .claude/hooks/breaker_guard.py (P27)."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HOOK_PATH = REPO_ROOT / ".claude" / "hooks" / "breaker_guard.py"

sys.path.insert(0, str(REPO_ROOT / ".claude" / "hooks"))
from breaker_guard import evaluate_bash as check_bash_command, evaluate_write_or_edit as check_write_path  # noqa: E402


@pytest.mark.parametrize(
    "cmd",
    [
        "ssh root@10.8.0.1",
        "scp dump.sql user@staging.neogold.example:/tmp/",
        "wg show interfaces",
        "wg-quick up wg0",
        "neogold-deploy staging",
        "neogold-admin study close",
        "docker exec api neogold-admin participant withdraw SKP-0001",
        "restic snapshots",
        "git push origin main",
        "gh release create v1.0.0",
        "python3 tools/play_publisher.py --bundle app.aab",
        "./gradlew publishReleaseBundle",
        "curl https://staging.neogold.kitalonlabs.com/v1/health",
        "curl https://intake.neogold.org/v1/device",
        "http://192.168.1.50:8000/v1/health",
    ],
)
def test_breaker_guard_denies_forbidden_bash_commands_and_external_urls(cmd: str):
    reason = check_bash_command(cmd)
    assert reason is not None, f"Expected command to be denied: {cmd}"


@pytest.mark.parametrize(
    "cmd",
    [
        "bash breaker/env/guard.sh --api-url http://127.0.0.1:18000/v1",
        "curl -s http://localhost:18000/v1/health",
        "curl -s http://127.0.0.1:18000/v1/device",
        "curl -s http://10.0.2.2:18000/v1/health",
        "curl -s http://api:8000/v1/health",
        "pytest breaker/tests/test_brk_findings.py",
        "python3 breaker/run_round.py --area api --rounds 1",
    ],
)
def test_breaker_guard_allows_breaker_local_commands(cmd: str):
    assert check_bash_command(cmd) is None


@pytest.mark.parametrize(
    "path",
    [
        "breaker/findings/BRK-20261031-01.md",
        str(REPO_ROOT / "breaker" / "findings" / "BRK-20261031-02.md"),
        "breaker/tests/test_brk_findings.py",
        "backend/tests/test_brk_session_withdrawal.py",
        "android/engine/triage/src/test/kotlin/com/kitalonlabs/neogold/engine/triage/BrkBoundaryTest.kt",
        "android/app/src/androidTest/kotlin/com/kitalonlabs/neogold/BrkUiMonkeyTest.kt",
    ],
)
def test_breaker_guard_allows_findings_and_breaker_test_writes(path: str):
    assert check_write_path(path) is None


@pytest.mark.parametrize(
    "path",
    [
        "backend/app/routers/sessions.py",
        "backend/app/cli.py",
        "android/engine/triage/src/main/kotlin/com/kitalonlabs/neogold/engine/triage/TriageEngine.kt",
        "config/triage-config.json",
        "docs/7_API_Documentation.md",
        "breaker/catalog.md",
        "breaker/env/guard.sh",
        "backend/tests/test_sessions.py",
    ],
)
def test_breaker_guard_denies_writes_outside_findings_and_breaker_tests(path: str):
    reason = check_write_path(path)
    assert reason is not None, f"Expected write to {path} to be denied"


def test_hook_protocol_denies_write_to_app_code():
    payload = json.dumps(
        {
            "tool_name": "Write",
            "tool_input": {"file_path": "backend/app/routers/sessions.py"},
        }
    )
    res = subprocess.run([sys.executable, str(HOOK_PATH)], input=payload, capture_output=True, text=True, check=False)
    assert res.returncode == 2
    data = json.loads(res.stdout)
    assert data["hookSpecificOutput"]["permissionDecision"] == "deny"


def test_hook_protocol_project_mode_ignores_non_breaker_agents():
    payload = json.dumps(
        {
            "agent_type": "main",
            "tool_name": "Write",
            "tool_input": {"file_path": "backend/app/routers/sessions.py"},
        }
    )
    res = subprocess.run(
        [sys.executable, str(HOOK_PATH), "--project"],
        input=payload,
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 0


def test_hook_protocol_project_mode_enforces_for_breaker_agent():
    payload = json.dumps(
        {
            "agent_type": "breaker",
            "tool_name": "Bash",
            "tool_input": {"command": "curl https://staging.neogold.example/v1/health"},
        }
    )
    res = subprocess.run(
        [sys.executable, str(HOOK_PATH), "--project"],
        input=payload,
        capture_output=True,
        text=True,
        check=False,
    )
    assert res.returncode == 2
