"""Tests for .claude/hooks/fresh_eyes_guard.py (P28).

Specifically verifies P28 Constraints:
"The fresh-eyes agent never sees source code, docs, CLAUDE.md, resource IDs, or test names.
Prove it in the PR with a test run where each of these attempts is denied:
`cat`, `ls`, a Read on a source file, and `drive.sh see; cat x`."
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent
HOOK_PATH = REPO_ROOT / ".claude" / "hooks" / "fresh_eyes_guard.py"

sys.path.insert(0, str(REPO_ROOT / ".claude" / "hooks"))
from fresh_eyes_guard import evaluate_bash, evaluate_read  # noqa: E402


def run_hook(payload: dict, project_mode: bool = False) -> subprocess.CompletedProcess[str]:
    cmd = [sys.executable, str(HOOK_PATH)]
    if project_mode:
        cmd.append("--project")
    return subprocess.run(
        cmd,
        input=json.dumps(payload),
        capture_output=True,
        text=True,
        check=False,
    )


def test_p28_mandatory_four_denial_proofs():
    """Prove the four mandatory P28 denial attempts fail with exit code 2 and permissionDecision=deny."""
    # 1. `cat`
    res_cat = run_hook({"tool_name": "Bash", "tool_input": {"command": "cat CLAUDE.md"}})
    assert res_cat.returncode == 2
    assert json.loads(res_cat.stdout)["hookSpecificOutput"]["permissionDecision"] == "deny"

    # 2. `ls`
    res_ls = run_hook({"tool_name": "Bash", "tool_input": {"command": "ls -la"}})
    assert res_ls.returncode == 2
    assert json.loads(res_ls.stdout)["hookSpecificOutput"]["permissionDecision"] == "deny"

    # 3. `Read` on a source file
    res_read_src = run_hook(
        {
            "tool_name": "Read",
            "tool_input": {
                "file_path": "android/app/src/main/kotlin/com/kitalonlabs/neogold/MainActivity.kt"
            },
        }
    )
    assert res_read_src.returncode == 2
    assert json.loads(res_read_src.stdout)["hookSpecificOutput"]["permissionDecision"] == "deny"

    # 4. `drive.sh see; cat x`
    res_chain = run_hook(
        {"tool_name": "Bash", "tool_input": {"command": "tools/ux/drive.sh see; cat x"}}
    )
    assert res_chain.returncode == 2
    assert json.loads(res_chain.stdout)["hookSpecificOutput"]["permissionDecision"] == "deny"


@pytest.mark.parametrize(
    "cmd",
    [
        "tools/ux/drive.sh launch",
        "./tools/ux/drive.sh see",
        "bash tools/ux/drive.sh see",
        "tools/ux/drive.sh shot 01-home",
        "tools/ux/drive.sh tap 540 1200",
        'tools/ux/drive.sh tap-text "New screening"',
        'tools/ux/drive.sh tap-text "নতুন স্ক্রিনিং"',
        'tools/ux/drive.sh type "7K2X-M49P-3QW8"',
        'tools/ux/drive.sh type "12/345"',
        "tools/ux/drive.sh swipe up",
        "tools/ux/drive.sh swipe down",
        "tools/ux/drive.sh swipe left",
        "tools/ux/drive.sh swipe right",
        "tools/ux/drive.sh back",
        "tools/ux/drive.sh home",
        "tools/ux/drive.sh wait 2",
    ],
)
def test_fresh_eyes_allows_valid_drive_commands(cmd: str):
    assert evaluate_bash(cmd) is None
    res = run_hook({"tool_name": "Bash", "tool_input": {"command": cmd}})
    assert res.returncode == 0


@pytest.mark.parametrize(
    "cmd",
    [
        "cat CLAUDE.md",
        "ls",
        "tools/ux/drive.sh see; cat x",
        "tools/ux/drive.sh see && ls",
        "tools/ux/drive.sh see | head",
        "tools/ux/drive.sh tap-text \"$(cat secret)\"",
        "tools/ux/drive.sh tap-text `id`",
        "tools/ux/drive.sh see > /tmp/out",
        "tools/ux/drive.sh unknown-sub",
        "tools/ux/drive.sh tap abc def",
        "tools/ux/drive.sh swipe diagonal",
        "tools/ux/drive.sh shot ../escape",
        "python3 tools/ux/adb_sim.py",
        "adb shell uiautomator dump",
    ],
)
def test_fresh_eyes_denies_invalid_or_unsafe_bash(cmd: str):
    reason = evaluate_bash(cmd)
    assert reason is not None, f"Expected command to be denied: {cmd}"


@pytest.mark.parametrize(
    "path",
    [
        "shots/01_home.png",
        "./shots/02-consent.png",
        "/tmp/neogold-fresh-eyes-run1/shots/screen_01.png",
        "reports/ux/20261102-rokeya-01/shots/s01.png",
    ],
)
def test_fresh_eyes_allows_png_reads_in_shots_folder(path: str):
    assert evaluate_read(path) is None
    res = run_hook({"tool_name": "Read", "tool_input": {"file_path": path}})
    assert res.returncode == 0


@pytest.mark.parametrize(
    "path",
    [
        "CLAUDE.md",
        "docs/2_PRD.md",
        "docs/6_UI_UX_Guidelines.md",
        "android/app/src/main/kotlin/com/kitalonlabs/neogold/MainActivity.kt",
        "shots/../CLAUDE.md",
        "shots/notes.txt",
        "shots/dump.xml",
        "persona.md",
        "task.md",
        "actions.jsonl",
        "android/shots/fake.png",
    ],
)
def test_fresh_eyes_denies_non_shot_reads(path: str):
    reason = evaluate_read(path)
    assert reason is not None, f"Expected Read path to be denied: {path}"


def test_fresh_eyes_denies_other_tools_and_respects_project_mode():
    # Write / Grep / Glob are denied for fresh-eyes
    for tool_name in ("Write", "Edit", "Glob", "Grep"):
        res = run_hook(
            {
                "agent_type": "fresh-eyes",
                "tool_name": tool_name,
                "tool_input": {"file_path": "shots/a.png"},
            },
            project_mode=True,
        )
        assert res.returncode == 2

    # In --project mode, other agents or main session (no agent_type) are unaffected
    res_main = run_hook(
        {"tool_name": "Bash", "tool_input": {"command": "ls -la"}},
        project_mode=True,
    )
    assert res_main.returncode == 0

