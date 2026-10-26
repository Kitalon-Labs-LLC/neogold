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
    "python3 tools/drift_inventory.py --python=/opt/venv/bin/python",
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
