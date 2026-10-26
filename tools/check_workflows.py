#!/usr/bin/env python3
"""CI guard: every GitHub Actions workflow must parse and have the expected shape.

Usage: python3 tools/check_workflows.py [FILE ...]
With no arguments it checks .github/workflows/*.yml and docs/ci-templates/*.yml.

Why this exists (BUG-2026-014, TC-OPS-01): an unquoted colon in a step name made
android-ci.yml and android-release.yml invalid YAML. GitHub then starts no job at all,
so the failure looked like "no CI" instead of "broken CI" and went unnoticed for days.

Checks:
1. The file parses as YAML and is a mapping with a `jobs` mapping.
2. Every job has `steps` or `uses`, and every step has `run` or `uses`.
3. In .github/workflows only: every external `uses:` is pinned to a full 40-hex commit SHA
   (CLAUDE.md, Security). Local `./` workflow references are allowed.

Exits 0 when everything passes, 1 otherwise. Needs PyYAML (it is in backend/requirements.lock).
"""

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("ERROR: PyYAML required. Run: pip install --require-hashes -r backend/requirements.lock", file=sys.stderr)
    sys.exit(2)

REPO = Path(__file__).resolve().parent.parent
SHA_PIN = re.compile(r"@[0-9a-f]{40}(\s|$)")


def check_workflow(path: Path, require_pins: bool) -> list[str]:
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
        if "uses" in job:
            problems += _pin_problems(path, f"job `{job_name}`", job["uses"], require_pins)
            continue
        steps = job.get("steps")
        if not isinstance(steps, list) or not steps:
            problems.append(f"{path}: job `{job_name}` has no steps")
            continue
        for index, step in enumerate(steps, start=1):
            label = f"job `{job_name}` step {index}"
            if not isinstance(step, dict) or not ("run" in step or "uses" in step):
                problems.append(f"{path}: {label} has neither `run` nor `uses`")
                continue
            if "uses" in step:
                problems += _pin_problems(path, label, step["uses"], require_pins)
    return problems


def _pin_problems(path: Path, label: str, uses: object, require_pins: bool) -> list[str]:
    if not require_pins or not isinstance(uses, str) or uses.startswith("./"):
        return []
    if not SHA_PIN.search(uses):
        return [f"{path}: {label} uses `{uses}` which is not pinned to a full commit SHA"]
    return []


def _where(error: yaml.YAMLError) -> str:
    mark = getattr(error, "problem_mark", None)
    return f"line {mark.line + 1}, column {mark.column + 1}" if mark else "unknown position"


def default_targets() -> list[tuple[Path, bool]]:
    workflows = sorted((REPO / ".github" / "workflows").glob("*.yml"))
    templates = sorted((REPO / "docs" / "ci-templates").glob("*.yml"))
    return [(p, True) for p in workflows] + [(p, False) for p in templates]


def main(argv: list[str]) -> int:
    targets = [(Path(a), True) for a in argv] if argv else default_targets()
    if not targets:
        print("FAIL: no workflow files found", file=sys.stderr)
        return 1
    problems: list[str] = []
    for path, require_pins in targets:
        problems += check_workflow(path, require_pins)
    if problems:
        print("\n".join(problems), file=sys.stderr)
        print(f"FAIL: {len(problems)} problem(s) in {len(targets)} workflow file(s)", file=sys.stderr)
        return 1
    print(f"PASS: {len(targets)} workflow file(s) parse and are well formed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
