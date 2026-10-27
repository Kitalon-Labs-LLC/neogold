"""Tooling tests for tools/check_commit_attribution.py (repository rule: no AI attribution on commits)."""

import importlib.util
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
TOOL = REPO / "tools" / "check_commit_attribution.py"
spec = importlib.util.spec_from_file_location("check_commit_attribution", TOOL)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


@pytest.mark.parametrize("line", [
    "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>",
    "co-authored-by: claude <anything@example.com>",
    "  Co-authored-by: Claude Opus 5.5 <noreply@anthropic.com>",
    "Co-Authored-By: Someone Else <noreply@anthropic.com>",
    "Generated with [Claude Code](https://claude.com/claude-code)",
    "🤖 Generated with Claude Code",
])
def test_attribution_lines_are_found(line):
    assert check.find_attribution(f"fix: something\n\nbody text\n\n{line}\n") == [line.strip()]


@pytest.mark.parametrize("message", [
    "fix: ordinary message\n\nNo trailer here.",
    "docs: update CLAUDE.md commands table",
    "chore: ignore local Claude Code and Agent Quality prompt markdown files",
    "docs: move the .claude/settings.json hook description",
    "Co-Authored-By: Jane Doe <jane@example.com>",
])
def test_ordinary_mentions_and_human_coauthors_are_not_flagged(message):
    assert check.find_attribution(message) == []


def test_each_offending_line_is_reported_once():
    message = "x\n\nCo-Authored-By: Claude A <a@anthropic.com>\nGenerated with Claude Code\n"
    assert len(check.find_attribution(message)) == 2


def _git(cwd, *args):
    return subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", *args],
        cwd=cwd, capture_output=True, text=True, check=True,
    ).stdout.strip()


def _repo_with_commits(tmp_path, messages):
    _git(tmp_path, "init", "-q")
    for i, msg in enumerate(messages):
        (tmp_path / f"f{i}.txt").write_text(str(i), encoding="utf-8")
        _git(tmp_path, "add", "-A")
        _git(tmp_path, "commit", "-q", "-m", msg)
    return _git(tmp_path, "rev-list", "--max-parents=0", "HEAD")


def test_range_check_reports_only_commits_in_the_range(tmp_path):
    base = _repo_with_commits(tmp_path, [
        "first\n\nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>",  # outside the range
        "second: clean",
        "third\n\nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>",
    ])
    problems = check.check_range(f"{base}..HEAD", cwd=tmp_path)
    assert len(problems) == 1 and "third" in problems[0]


def test_range_check_passes_for_clean_history(tmp_path):
    base = _repo_with_commits(tmp_path, ["first", "second", "third"])
    assert check.check_range(f"{base}..HEAD", cwd=tmp_path) == []


def test_message_file_mode_blocks_for_the_commit_msg_hook(tmp_path):
    bad = tmp_path / "msg"
    bad.write_text("fix: x\n\nCo-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>\n", encoding="utf-8")
    result = subprocess.run([sys.executable, str(TOOL), "--message-file", str(bad)], capture_output=True, text=True)
    assert result.returncode == 1 and "Co-Authored-By" in result.stderr

    good = tmp_path / "ok"
    good.write_text("fix: x\n", encoding="utf-8")
    assert subprocess.run([sys.executable, str(TOOL), "--message-file", str(good)]).returncode == 0


def test_missing_arguments_is_a_usage_error():
    result = subprocess.run([sys.executable, str(TOOL)], capture_output=True, text=True)
    assert result.returncode == 2


def test_no_commit_in_this_repository_since_the_rule_carries_attribution():
    # `origin/dev..HEAD` is empty on a fresh clone of dev, so this only guards new local commits.
    base = subprocess.run(["git", "rev-parse", "--verify", "-q", "origin/dev"], cwd=REPO, capture_output=True, text=True)
    if base.returncode != 0:
        pytest.skip("origin/dev is not available")
    assert check.check_range("origin/dev..HEAD", cwd=REPO) == []


HOOK = REPO / "tools" / "hooks" / "commit-msg"
HOOK_CASES = [
    ("Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>", 1),
    ("co-authored-by: claude <anything@example.com>", 1),
    ("Co-Authored-By: Someone <noreply@anthropic.com>", 1),
    ("Generated with [Claude Code](https://claude.com/claude-code)", 1),
    ("Co-Authored-By: Jane Doe <jane@example.com>", 0),
    ("docs: update CLAUDE.md commands table", 0),
]


@pytest.mark.skipif(shutil.which("sh") is None, reason="sh is not available")
@pytest.mark.parametrize("line,expected", HOOK_CASES)
def test_shell_hook_agrees_with_the_python_checker(tmp_path, line, expected):
    message = tmp_path / "msg"
    message.write_text(f"fix: something\n\n{line}\n", encoding="utf-8")
    hook = subprocess.run(["sh", str(HOOK), str(message)], capture_output=True, text=True)
    python_says = 1 if check.find_attribution(message.read_text(encoding="utf-8")) else 0
    assert hook.returncode == expected == python_says
