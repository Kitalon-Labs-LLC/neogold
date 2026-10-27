#!/usr/bin/env python3
"""CI guard: every GitHub Actions workflow must parse and have runnable steps.

Usage: python3 tools/check_workflows.py [FILE ...]
With no arguments it checks .github/workflows/*.yml and docs/ci-templates/*.yml.

Why this exists (BUG-2026-014, TC-OPS-01): an unquoted colon in a step name made
android-ci.yml and android-release.yml invalid YAML. GitHub then starts no job at all,
so the failure looked like "no CI" instead of "broken CI" and went unnoticed for days.
The same mistake was made again in repo-ci.yml (`DATABASE_URL: sqlite:///:memory:`).

Checks:
1. The file parses as YAML and is a mapping with a `jobs` mapping.
2. Every job has `steps` or `uses`, and every step has `run` or `uses`.

Pinning actions to a full commit SHA is a separate rule, owned by tools/check_actions_pinned.py.

Exits 0 when everything passes, 1 otherwise. Needs PyYAML.
"""

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("ERROR: PyYAML required. Run: pip install --require-hashes --only-binary :all: -r backend/requirements.lock", file=sys.stderr)
    sys.exit(2)

REPO = Path(__file__).resolve().parent.parent


def check_workflow(path: Path) -> list[str]:
    """Return a list of human-readable problems for one workflow file (empty means OK)."""
    try:
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    except OSError as e:
        return [f"{path}: cannot read file: {e.strerror or e}"]
    except yaml.YAMLError as e:
        return [f"{path}: invalid YAML: {str(e).splitlines()[0]} ({_where(e)})"]

    if not isinstance(doc, dict) or not isinstance(doc.get("jobs"), dict):
        return [f"{path}: not a workflow (expected a mapping with a `jobs` mapping)"]

    problems: list[str] = []
    for job_name, job in doc["jobs"].items():
        if not isinstance(job, dict):
            problems.append(f"{path}: job `{job_name}` is not a mapping")
            continue
        if "uses" in job:  # a reusable workflow call has no steps
            continue
        steps = job.get("steps")
        if not isinstance(steps, list) or not steps:
            problems.append(f"{path}: job `{job_name}` has no steps")
            continue
        for index, step in enumerate(steps, start=1):
            if not isinstance(step, dict) or not ("run" in step or "uses" in step):
                problems.append(f"{path}: job `{job_name}` step {index} has neither `run` nor `uses`")
    return problems


def _where(error: yaml.YAMLError) -> str:
    mark = getattr(error, "problem_mark", None)
    return f"line {mark.line + 1}, column {mark.column + 1}" if mark else "unknown position"


def default_targets() -> list[Path]:
    workflows = sorted((REPO / ".github" / "workflows").glob("*.yml"))
    templates = sorted((REPO / "docs" / "ci-templates").glob("*.yml"))
    return workflows + templates


def main(argv: list[str]) -> int:
    targets = [Path(a) for a in argv] if argv else default_targets()
    if not targets:
        print("FAIL: no workflow files found", file=sys.stderr)
        return 1
    problems: list[str] = []
    for path in targets:
        problems += check_workflow(path)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        print(f"FAIL: {len(problems)} problem(s) in {len(targets)} workflow file(s)", file=sys.stderr)
        return 1
    print(f"PASS: {len(targets)} workflow file(s) parse and are well formed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
