"""Tooling tests for the doc-auditor Bash guard hook (P24)."""

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
GUARD = REPO / ".claude/hooks/doc_auditor_bash_guard.py"
spec = importlib.util.spec_from_file_location("guard", GUARD)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


@pytest.mark.parametrize("command", [
    "git log --oneline -20",
    "git log --format=%H%x1f%s -- docs/4_System_Architecture.md",
    "git show HEAD:docs/2_PRD.md",
    "git diff main...HEAD --stat",
    "python3 tools/drift_inventory.py",
    "python3 tools/drift_inventory.py --since abc123 --check --allow-skipped",
    "python3 tools/drift_inventory.py --since=p24-direction-check",
    "python3 tools/drift_inventory.py --since HEAD~5",
    "git log main..HEAD",
    "python3 tools/trace.py docs",
    "python3 tools/trace.py docs --tests",
])
def test_allowed(command):
    assert guard.verdict(command) is None


@pytest.mark.parametrize("command", [
    "python3 tools/trace.py docs --write",            # rewrites doc 10
    "python3 tools/assumptions.py --report",          # writes reports/
    "python3 tools/drift_inventory.py --write-baseline",
    "python3 tools/drift_inventory.py --out reports/direction/x.json",
    "python3 tools/drift_inventory.py --root /tmp",
    "python3 tools/drift_inventory.py --since=--output=docs/x",   # privacy review finding 1
    "python3 tools/drift_inventory.py --since --output=docs/x",
    "python3 tools/drift_inventory.py --since",
    "python3 tools/drift_inventory.py --python /bin/sh",          # finding 2
    "python3 tools/drift_inventory.py --merged-manifest /etc/passwd",
    "git diff /etc/hostname /dev/null",                           # finding 4
    "git diff ../other-checkout/file",
    "git diff ~/notes",
    "python3 tools/verify_config_schema.py",
    "python3 -c 'import os'",
    "python3 tools/../backend/app/cli.py",
    "git log; rm -rf android",
    "git log && touch x",
    "git log | tee docs/x.md",
    "git log > docs/x.md",
    "git log $(rm -rf .)",
    "git log `id`",
    "git diff --output=docs/x.md",
    "git log -o x",
    "git diff --ext-diff",
    "git diff --no-index /etc/passwd /dev/null",
    "git -c core.pager=sh log",
    "git checkout main",
    "git commit -m x",
    "git push",
    "rm -rf android",
    "sed -i s/a/b/ docs/2_PRD.md",
    "cat docs/2_PRD.md",
    "echo hi\nrm x",
    "",
])
def test_blocked(command):
    assert guard.verdict(command) is not None


def run_hook(payload):
    return subprocess.run([sys.executable, str(GUARD)], input=payload, capture_output=True, text=True)


def test_hook_protocol_blocks_with_exit_2_and_reason():
    res = run_hook(json.dumps({"tool_name": "Bash", "tool_input": {"command": "git push"}}))
    assert res.returncode == 2
    assert "doc-auditor guard blocked" in res.stderr


def test_hook_protocol_allows_with_exit_0():
    res = run_hook(json.dumps({"tool_name": "Bash", "tool_input": {"command": "git log -1"}}))
    assert res.returncode == 0


def test_hook_fails_closed_on_bad_input():
    assert run_hook("not json").returncode == 2
    assert run_hook("[1, 2]").returncode == 2
    bad_type = json.dumps({"tool_name": "Bash", "tool_input": {"command": ["git", "log"]}})
    assert run_hook(bad_type).returncode == 2


def run_project_hook(payload):
    return subprocess.run([sys.executable, str(GUARD), "--project"], input=payload, capture_output=True, text=True)


def test_project_hook_never_blocks_main_session_on_bad_input():
    assert run_project_hook("not json").returncode == 0
    assert run_project_hook("[1, 2]").returncode == 0


def test_project_hook_fails_closed_for_auditor_on_bad_command_type():
    bad = json.dumps({"tool_name": "Bash", "agent_type": "doc-auditor", "tool_input": {"command": 42}})
    res = run_project_hook(bad)
    assert res.returncode == 2 and "guard error" in res.stderr


def test_agent_frontmatter_wires_the_guard():
    text = (REPO / ".claude/agents/doc-auditor.md").read_text()
    head = text.split("---")[1]
    assert "tools: Read, Grep, Glob, Bash" in head
    assert "PreToolUse" in head and 'matcher: "Bash"' in head
    assert ".claude/hooks/doc_auditor_bash_guard.py" in head


def _sample_claim(**over):
    claim = {"id": "FR-08", "kind": "FR", "section": "7.2", "claim": "R1 wins over R3",
             "status": "Implemented", "area": "triage", "milestone": "M1",
             "evidence": [{"file": "android/engine/triage/src/main/kotlin/T.kt", "line": 25}]}
    claim.update(over)
    return claim


def _report(claims):
    return {"doc": "docs/4_System_Architecture.md", "commit": "4523908", "inventory": "x.json",
            "claims": claims, "counts": dict.fromkeys(("Implemented", "Partial", "Missing", "Contradicted", "Deferred", "Unknown"), 0)}


def test_auditor_schema_requires_evidence_for_implemented():
    jsonschema = pytest.importorskip("jsonschema")
    schema = json.loads((REPO / "reports/direction/auditor.schema.json").read_text())
    jsonschema.validate(_report([_sample_claim()]), schema)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(_report([_sample_claim(evidence=[])]), schema)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(_report([_sample_claim(status="Unknown", evidence=[])]), schema)  # needs reason
    jsonschema.validate(_report([_sample_claim(status="Unknown", evidence=[], reason="needs a phone")]), schema)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(_report([_sample_claim(status="Done")]), schema)


def test_project_registration_enforces_only_inside_doc_auditor():
    cmd = {"tool_name": "Bash", "tool_input": {"command": "git log; rm -rf android"}}
    project = [sys.executable, str(GUARD), "--project"]
    inside = subprocess.run(project, input=json.dumps({**cmd, "agent_type": "doc-auditor"}),
                            capture_output=True, text=True)
    assert inside.returncode == 2
    for other in ({**cmd, "agent_type": "general-purpose"}, cmd):  # other subagents, main session
        assert subprocess.run(project, input=json.dumps(other), capture_output=True, text=True).returncode == 0


def test_project_settings_register_the_guard():
    settings = json.loads((REPO / ".claude/settings.json").read_text())
    hooks = [h["command"] for e in settings["hooks"]["PreToolUse"] if e["matcher"] == "Bash" for h in e["hooks"]]
    assert any("doc_auditor_bash_guard.py" in c and "--project" in c for c in hooks)
