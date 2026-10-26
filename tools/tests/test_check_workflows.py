"""Tooling tests for tools/check_workflows.py (BUG-2026-014, TC-OPS-01)."""

import importlib.util
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
TOOL = REPO / "tools" / "check_workflows.py"
spec = importlib.util.spec_from_file_location("check_workflows", TOOL)
check_workflows = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_workflows)

PINNED = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"


def _write(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "wf.yml"
    path.write_text(body, encoding="utf-8")
    return path


def test_unquoted_colon_in_step_name_is_reported_with_position(tmp_path):
    # The exact mistake that broke android-ci.yml and android-release.yml (BUG-2026-014).
    path = _write(tmp_path, f"jobs:\n  a:\n    steps:\n      - uses: {PINNED}\n      - name: Gate: license check\n        run: x\n")
    problems = check_workflows.check_workflow(path, require_pins=True)
    assert len(problems) == 1
    assert "invalid YAML" in problems[0] and "line 5" in problems[0]


def test_flag_text_with_colon_space_in_a_run_value_is_reported(tmp_path):
    # `--only-binary :all: -r` needs quotes, as repo-ci.yml already does.
    path = _write(tmp_path, f"jobs:\n  a:\n    steps:\n      - uses: {PINNED}\n      - run: pip install --only-binary :all: -r x\n")
    assert "invalid YAML" in check_workflows.check_workflow(path, require_pins=True)[0]


def test_well_formed_workflow_passes(tmp_path):
    path = _write(tmp_path, f"jobs:\n  a:\n    steps:\n      - uses: {PINNED}\n      - run: echo ok\n")
    assert check_workflows.check_workflow(path, require_pins=True) == []


def test_step_without_run_or_uses_is_reported(tmp_path):
    path = _write(tmp_path, f"jobs:\n  a:\n    steps:\n      - name: nothing to do\n")
    assert "neither `run` nor `uses`" in check_workflows.check_workflow(path, require_pins=True)[0]


def test_unpinned_action_is_reported_only_when_pins_are_required(tmp_path):
    path = _write(tmp_path, "jobs:\n  a:\n    steps:\n      - uses: actions/checkout@v4\n")
    assert "not pinned" in check_workflows.check_workflow(path, require_pins=True)[0]
    assert check_workflows.check_workflow(path, require_pins=False) == []


def test_local_reusable_workflow_needs_no_pin(tmp_path):
    path = _write(tmp_path, "jobs:\n  verify:\n    uses: ./.github/workflows/android-ci.yml\n")
    assert check_workflows.check_workflow(path, require_pins=True) == []


def test_missing_file_fails_cleanly_instead_of_crashing(tmp_path):
    problems = check_workflows.check_workflow(tmp_path / "absent.yml", require_pins=True)
    assert len(problems) == 1 and "cannot read file" in problems[0]


def test_not_a_workflow_is_reported(tmp_path):
    path = _write(tmp_path, "just: a mapping\n")
    assert "not a workflow" in check_workflows.check_workflow(path, require_pins=True)[0]


def test_every_workflow_in_this_repository_passes():
    result = subprocess.run([sys.executable, str(TOOL)], cwd=REPO, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
