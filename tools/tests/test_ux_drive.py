"""Tests for P28 Fresh-eyes UX tooling (`tools/ux/drive.sh`, `drive_core.py`, `ui_dump_parser.py`, `run-fresh-eyes.sh`)."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
UX_DIR = REPO_ROOT / "tools" / "ux"
DRIVE_SH = UX_DIR / "drive.sh"
RUN_FRESH_EYES_SH = UX_DIR / "run-fresh-eyes.sh"

sys.path.insert(0, str(UX_DIR))
from drive_core import execute_drive_command  # noqa: E402
from ui_dump_parser import find_label_center, format_see_output  # noqa: E402
from ux_runner import run_in_external_sandbox  # noqa: E402

SAMPLE_UIAUTOMATOR_XML = """<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node index="0" text="NeoGold" resource-id="com.kitalonlabs.neogold:id/s09_title"
        class="android.widget.TextView" package="com.kitalonlabs.neogold"
        content-desc="" clickable="false" enabled="true" checked="false"
        scrollable="false" focused="false" bounds="[60,100][700,200]" />
  <node index="1" text="New screening" resource-id="com.kitalonlabs.neogold:id/s09_new_screening"
        class="android.widget.Button" package="com.kitalonlabs.neogold"
        content-desc="Start new screening" clickable="true" enabled="true" checked="false"
        scrollable="false" focused="false" bounds="[80,400][1000,620]" />
  <node index="2" text="" resource-id="com.kitalonlabs.neogold:id/s09_settings_btn"
        class="android.widget.ImageButton" package="com.kitalonlabs.neogold"
        content-desc="Settings" clickable="true" enabled="true" checked="false"
        scrollable="false" focused="false" bounds="[900,80][1040,220]" />
</hierarchy>
"""


def test_ui_dump_parser_strips_resource_ids_classes_and_packages():
    output = format_see_output(SAMPLE_UIAUTOMATOR_XML)
    assert "New screening" in output
    assert "Settings" in output
    assert "[80,400][1000,620]" in output
    assert "clickable=true" in output

    # Must NEVER contain resource-id, class name, or package name
    assert "com.kitalonlabs.neogold" not in output
    assert "s09_new_screening" not in output
    assert "s09_title" not in output
    assert "android.widget" not in output


def test_ui_dump_parser_find_label_center():
    assert find_label_center(SAMPLE_UIAUTOMATOR_XML, "New screening") == (540, 510)
    assert find_label_center(SAMPLE_UIAUTOMATOR_XML, "Settings") == (970, 150)
    assert find_label_center(SAMPLE_UIAUTOMATOR_XML, "Nonexistent Label") is None


def test_drive_commands_and_actions_jsonl(tmp_path: Path):
    env = os.environ.copy()
    env["NEOGOLD_UX_SIM"] = "1"
    env["FRESH_EYES_RUN_DIR"] = str(tmp_path)
    env["FRESH_EYES_SHOTS_DIR"] = str(tmp_path / "shots")
    env["NEOGOLD_SIM_TASK"] = "01-ward-setup"
    env["NEOGOLD_SIM_LANG"] = "en"

    rc, _, _ = execute_drive_command(["launch"], env=env)
    assert rc == 0

    rc, see_out, _ = execute_drive_command(["see"], env=env)
    assert rc == 0
    assert "com.kitalonlabs.neogold" not in see_out
    assert "English" in see_out

    rc, shot_out, _ = execute_drive_command(["shot", "01_start"], env=env)
    assert rc == 0
    assert shot_out.strip() == "shots/01_start.png"
    shot_file = tmp_path / "shots" / "01_start.png"
    assert shot_file.exists()
    assert shot_file.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")

    assert execute_drive_command(["tap-text", "English"], env=env)[0] == 0
    assert execute_drive_command(["tap-text", "Continue"], env=env)[0] == 0
    assert execute_drive_command(["wait", "1"], env=env)[0] == 0

    # Verify invalid arguments are strictly rejected
    assert execute_drive_command(["shot", "../bad"], env=env)[0] != 0
    assert execute_drive_command(["tap", "abc", "100"], env=env)[0] != 0
    assert execute_drive_command(["swipe", "diagonal"], env=env)[0] != 0
    assert execute_drive_command(["unknown"], env=env)[0] != 0

    # Verify actions.jsonl has timestamped records for all successful commands
    actions_log = tmp_path / "actions.jsonl"
    assert actions_log.exists()
    entries = [json.loads(line) for line in actions_log.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert [e["action"] for e in entries] == ["launch", "see", "shot", "tap-text", "tap-text", "wait"]
    for entry in entries:
        assert "timestamp" in entry and "T" in entry["timestamp"]


def test_external_sandbox_run_and_cleanup():
    run_id = "pytest-sandbox-check"
    report_dir = REPO_ROOT / "reports" / "ux" / run_id
    shutil.rmtree(report_dir, ignore_errors=True)
    try:
        report_md = run_in_external_sandbox(
            persona_arg="rokeya-nurse",
            task_arg="01-ward-setup",
            lang_override="en",
            run_id_override=run_id,
        )
        assert report_md.exists()
        assert (report_dir / "actions.jsonl").exists()
        assert list((report_dir / "shots").glob("*.png"))
        txt = report_md.read_text(encoding="utf-8")
        assert "**Outcome**" in txt
        assert "**Taps**" in txt
        assert "## 2. Friction Points" in txt
    finally:
        shutil.rmtree(report_dir, ignore_errors=True)


def test_agent_and_skill_frontmatter_metadata():
    agent_md = (REPO_ROOT / ".claude" / "agents" / "fresh-eyes.md").read_text(encoding="utf-8")
    assert "tools: Bash, Read" in agent_md
    assert "omitClaudeMd: true" in agent_md
    assert "fresh_eyes_guard.py" in agent_md

    skill_md = (REPO_ROOT / ".claude" / "skills" / "ng-fresh-eyes" / "SKILL.md").read_text(encoding="utf-8")
    assert "disable-model-invocation: true" in skill_md

    # Verify drive.sh and run-fresh-eyes.sh contain no eval
    for script in (DRIVE_SH, RUN_FRESH_EYES_SH):
        txt = script.read_text(encoding="utf-8")
        assert "eval " not in txt

    # Verify all personas and 6 tasks exist and do not leak screen IDs (S01..S25, R01..R09)
    personas = list((UX_DIR / "personas").glob("*.md"))
    tasks = sorted((UX_DIR / "tasks").glob("*.md"))
    assert len(personas) == 2
    assert len(tasks) == 6
    for f in personas + tasks:
        txt = f.read_text(encoding="utf-8")
        for sid in ("S01", "S09", "S10", "S16", "S17", "S20", "S23", "R01", "R02", "R07", "R08"):
            assert sid not in txt, f"{f.name} must not mention internal screen ID {sid}"
