"""Check that the image's runtime lock pins the same versions the tests ran on.

backend/requirements-runtime.lock (installed in the image) must be a subset of
backend/requirements.lock (installed in CI for tests), with identical versions.
Otherwise the deployed image could run code that no test ever exercised.

Usage: python tools/check_runtime_lock.py [runtime.lock] [full.lock]
Exit code 0 when consistent, 1 otherwise.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

_PIN = re.compile(r"^([A-Za-z0-9][A-Za-z0-9._-]*)(?:\[[^\]]*\])?==([^\s;\\]+)")


def read_pins(path: Path) -> dict[str, str]:
    pins: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = _PIN.match(line)
        if m:
            name = re.sub(r"[-_.]+", "-", m.group(1)).lower()
            pins[name] = m.group(2)
    return pins


def main(argv: list[str]) -> int:
    here = Path(__file__).resolve().parent.parent
    runtime = Path(argv[1]) if len(argv) > 1 else here / "requirements-runtime.lock"
    full = Path(argv[2]) if len(argv) > 2 else here / "requirements.lock"
    rt, fl = read_pins(runtime), read_pins(full)
    if not rt:
        print(f"FAIL: no pins found in {runtime}", file=sys.stderr)
        return 1
    problems = []
    for name, version in sorted(rt.items()):
        if name not in fl:
            problems.append(f"{name}=={version} is in the runtime lock but not in requirements.lock")
        elif fl[name] != version:
            problems.append(f"{name}: runtime {version} != tested {fl[name]}")
    for p in problems:
        print(f"FAIL: {p}", file=sys.stderr)
    if problems:
        print("Re-run the uv command in requirements-runtime.in.", file=sys.stderr)
        return 1
    print(f"runtime lock ok: {len(rt)} packages, all pinned as in requirements.lock")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
